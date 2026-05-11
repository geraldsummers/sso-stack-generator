#!/usr/bin/env bash
set -euo pipefail

echo "Waiting for Redis availability before configuring Mastodon recommendations..."
for i in $(seq 1 30); do
  if (echo > /dev/tcp/valkey/6379) >/dev/null 2>&1; then
    echo "Redis is reachable"
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "Redis is not reachable after retries; skipping recommendation configuration."
    exit 0
  fi
  sleep 2
done

mapfile -t recommended_accounts <<'EOF'
wikimediafoundation@wikimedia.social
internetarchive@mastodon.archive.org
creativecommons@mastodon.social
openstreetmap@en.osm.town
ProPublica@newsie.social
edyong209@mastodon.xyz
marynmck@mastodon.social
briankrebs@infosec.exchange
NASA@mstdn.social
sundogplanets@mastodon.social
ourworldindata@mas.to
AdamMGrant@mastodon.social
calnewport@mastodon.social
b0rk@jvns.ca
simon@fedi.simonwillison.net
prusaresearch@mastodon.social
VoronDesign@fosstodon.org
natgeo@mastodon.social
philosophybites@mastodon.social
tomscott@mastodon.social
standupmaths@mastodon.social
financialtimes@mastodon.social
EOF

recommended_accounts_csv="$(IFS=,; echo "${recommended_accounts[*]}")"
MASTODON_BOOTSTRAP_RECOMMENDATIONS="$recommended_accounts_csv" bundle exec rails runner '
  require "timeout"

  handles = ENV.fetch("MASTODON_BOOTSTRAP_RECOMMENDATIONS", "").split(",").map(&:strip).reject(&:empty?)
  resolved = []

  handles.each do |handle|
    attempts = 0
    begin
      account = Timeout.timeout(ENV.fetch("MASTODON_RECOMMENDATION_RESOLVE_TIMEOUT_SECONDS", "30").to_i) do
        ResolveAccountService.new.call(handle, skip_cache: true)
      end
      if account.nil?
        puts "warn recommendation: could not resolve #{handle}"
        next
      end
      if account.suspended? || account.silenced? || account.moved?
        puts "warn recommendation: skipping #{account.acct} because it is not recommendable"
        next
      end
      account.update!(discoverable: true) unless account.discoverable?
      resolved << account.acct
      puts "ok recommendation: resolved #{handle} as #{account.acct}"
    rescue Timeout::Error => e
      puts "warn recommendation: #{handle} timed out: #{e.class}"
    rescue => e
      attempts += 1
      transient = e.is_a?(ActiveRecord::ConnectionNotEstablished) ||
                  e.message.include?("database system is shutting down") ||
                  e.message.include?("Redis::CannotConnectError")
      if transient && attempts < 8
        puts "retry recommendation: #{handle} after transient error (#{e.class})"
        sleep 3
        retry
      end
      puts "warn recommendation: #{handle} failed: #{e.class} #{e.message}"
    end
  end

  unique = resolved.uniq
  Setting.bootstrap_timeline_accounts = unique.join(",")

  cache_root = Rails.root.join("public", "system", "cache").to_s
  cleaned = []
  models = ActiveRecord::Base.descendants.select do |model|
    begin
      !model.abstract_class? && model.table_exists?
    rescue
      false
    end
  end

  models.each do |model|
    model.column_names.grep(/_file_name$/).each do |column|
      attachment = column.sub(/_file_name$/, "")
      model.where.not(column => [nil, ""]).find_each do |record|
        file = record.public_send(attachment) rescue nil
        next unless file&.respond_to?(:path)

        path = begin
          file.path(:original)
        rescue
          begin
            file.path
          rescue
            nil
          end
        end

        next unless path.present? && path.start_with?(cache_root) && !File.exist?(path)

        file.clear
        record.save!(validate: false)
        cleaned << "#{model.name}(#{record.id}).#{attachment}"
      rescue => e
        puts "warn recommendation: failed cleaning #{model.name}(#{record.id}).#{attachment}: #{e.class} #{e.message}"
      end
    end
  end

  begin
    Rails.cache.delete_matched("follow_recommendations/*")
  rescue => e
    puts "warn recommendation: cache invalidation skipped: #{e.class} #{e.message}"
  end

  puts "configured bootstrap_timeline_accounts=#{Setting.bootstrap_timeline_accounts}"
  puts "cleaned missing cached recommendation attachments=#{cleaned.size}"
'

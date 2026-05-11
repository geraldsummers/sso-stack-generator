#!/usr/bin/env bash
set -euo pipefail

api_username="${MASTODON_API_USERNAME:-${STACK_ADMIN_USER:-sysadmin}}"
api_email="${MASTODON_API_EMAIL:-${STACK_ADMIN_EMAIL:-admin@webservices.net}}"
api_password="${MASTODON_API_PASSWORD:-${STACK_ADMIN_PASSWORD:-}}"
api_token="${MASTODON_API_TOKEN:-${api_password}}"

if [ -z "${api_password}" ]; then
  echo "[mastodon-api-user] MASTODON_API_PASSWORD/STACK_ADMIN_PASSWORD is empty, skipping local API user bootstrap"
  exit 0
fi

export webservices_API_USERNAME="${api_username}"
export webservices_API_EMAIL="${api_email}"
export webservices_API_PASSWORD="${api_password}"
export webservices_API_TOKEN="${api_token}"

bundle exec rails runner - <<'RUBY'
username = ENV.fetch('webservices_API_USERNAME').downcase
email = ENV.fetch('webservices_API_EMAIL').downcase
password = ENV.fetch('webservices_API_PASSWORD')

raise 'webservices_API_PASSWORD is empty' if password.empty?

account = Account.find_by(username: username, domain: nil)
unless account
  account = Account.new(username: username)
  account.display_name = username
  account.save!
end

user = User.find_by(email: email)
unless user
  user = User.new(email: email)
end

user.account ||= account
user.password = password
user.password_confirmation = password
user.agreement = true if user.respond_to?(:agreement=)
user.accepted_rules = true if user.respond_to?(:accepted_rules=)
user.accepted_terms_at ||= Time.now.utc if user.respond_to?(:accepted_terms_at=)
user.approved = true if user.respond_to?(:approved=)
user.confirmed_at ||= Time.now.utc
user.save!

# Mastodon callbacks can keep moderated instances in pending state.
# Force persisted approval fields after save to guarantee API test account usability.
force_updates = {}
force_updates[:approved] = true if user.has_attribute?(:approved)
force_updates[:disabled] = false if user.has_attribute?(:disabled)
force_updates[:confirmed_at] = user.confirmed_at || Time.now.utc if user.has_attribute?(:confirmed_at)
user.update_columns(force_updates) unless force_updates.empty?
user.reload

puts "[mastodon-api-user] ensured local API user #{username} (#{email})"

static_token = ENV.fetch('webservices_API_TOKEN', '').strip
unless static_token.empty?
  app = Doorkeeper::Application.find_or_create_by!(name: 'platform-integration-tests') do |application|
    application.redirect_uri = 'urn:ietf:wg:oauth:2.0:oob'
    application.scopes = 'read write follow'
  end

  token = Doorkeeper::AccessToken.find_by(token: static_token)
  token ||= Doorkeeper::AccessToken.where(application_id: app.id, resource_owner_id: user.id)
                                   .order(id: :desc)
                                   .first
  token ||= Doorkeeper::AccessToken.create!(
    application_id: app.id,
    resource_owner_id: user.id,
    scopes: 'read write follow',
    revoked_at: nil,
    expires_in: nil
  )

  # Keep the token deterministic without tripping the unique token constraint
  # on repeated init runs. If the static token already exists, reuse that row.
  token.update_columns(
    application_id: app.id,
    resource_owner_id: user.id,
    token: static_token,
    scopes: 'read write follow',
    revoked_at: nil,
    expires_in: nil
  )

  Doorkeeper::AccessToken.where(application_id: app.id, resource_owner_id: user.id)
                         .where.not(id: token.id)
                         .update_all(revoked_at: Time.now.utc)

  puts "[mastodon-api-user] ensured static API token for #{username}"
end
RUBY

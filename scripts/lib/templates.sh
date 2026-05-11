#!/usr/bin/env bash

# shellcheck source=scripts/lib/common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
# shellcheck source=scripts/lib/render-values.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/render-values.sh"

render_multiline_placeholder() {
  local file="$1"
  local key="$2"
  local value="$3"
  local temp_file
  temp_file="$(mktemp)"

  awk -v key="$key" -v value="$value" '
    function flush(prefix, content, n, lines, i) {
      n = split(content, lines, /\n/)
      for (i = 1; i <= n; ++i) {
        print prefix lines[i]
      }
    }
    {
      needle = "{{" key "}}"
      pos = index($0, needle)
      if (pos == 0) {
        print
        next
      }
      prefix = substr($0, 1, pos - 1)
      suffix = substr($0, pos + length(needle))
      if (suffix != "") {
        print "inline multiline placeholders are not supported for " key > "/dev/stderr"
        exit 2
      }
      flush(prefix, value)
    }
  ' "$file" > "$temp_file"

  mv "$temp_file" "$file"
}

render_moustache_file() {
  local src="$1"
  local dest="$2"
  local temp_file
  temp_file="$(mktemp)"
  cp "$src" "$temp_file"

  mapfile -t keys < <(grep -oE '\{\{[A-Z_][A-Z0-9_]*\}\}' "$src" | tr -d '{}' | sort -u || true)
  if [ "${#keys[@]}" -eq 0 ]; then
    cat "$temp_file" > "$dest"
    rm -f "$temp_file"
    return 0
  fi

  local envsubst_keys=""
  local sed_args=()
  local subst_keys=()
  local key
  for key in "${keys[@]}"; do
    render_has "$key" || die "missing template value: $key"
  done


  for key in "${keys[@]}"; do
    envsubst_keys="${envsubst_keys}\${$key} "
    subst_keys+=( "$key" )
    sed_args+=( -e "s|{{$key}}|\${$key}|g" )
  done

  if [ -n "$envsubst_keys" ]; then
    sed "${sed_args[@]}" "$temp_file" | render_envsubst "$envsubst_keys" "${subst_keys[@]}" > "$dest"
  else
    cat "$temp_file" > "$dest"
  fi

  rm -f "$temp_file"
}

render_config_tree() {
  local source_root="$1"
  local dest_root="$2"
  [ -d "$source_root" ] || return 0

  local source relative target
  while IFS= read -r source; do
    relative="${source#$source_root/}"
    target="$dest_root/$relative"
    if [[ "$target" == *.template ]]; then
      target="${target%.template}"
    fi
    mkdir -p "$(dirname "$target")"

    if grep -qE '\{\{[A-Z_][A-Z0-9_]*\}\}' "$source"; then
      render_moustache_file "$source" "$target"
    else
      cp "$source" "$target"
    fi

    if [ -x "$source" ]; then
      chmod +x "$target"
    fi
  done < <(find "$source_root" -type f | sort)
}

# frozen_string_literal: true

# Keep explicit OIDC anti-CSRF protections enabled even behind reverse proxies.
if defined?(Rails)
  Rails.application.config.to_prepare do
    next unless defined?(Devise)

    oidc_config = Devise.omniauth_configs[:openid_connect]
    next unless oidc_config

    oidc_config.options[:require_state] = true
    oidc_config.options[:send_state] = true
    oidc_config.options[:send_nonce] = true
  end
end

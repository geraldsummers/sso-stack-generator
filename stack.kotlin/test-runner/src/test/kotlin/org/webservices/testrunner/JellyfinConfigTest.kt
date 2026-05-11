package org.webservices.testrunner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JellyfinConfigTest {
    @Test
    fun `entrypoint does not precreate system config before first database initialization`() {
        val entrypoint = repoFileText("stack.config/jellyfin/entrypoint.sh")

        assertTrue(entrypoint.contains("mark_startup_wizard_completed()"))
        assertTrue(entrypoint.contains("Jellyfin system.xml not present yet"))
        assertTrue(entrypoint.contains(".manualLoginForm"))
        assertTrue(entrypoint.contains("Sign in with Keycloak"))
        assertFalse(
            entrypoint.contains("cat > \"\$system_config\""),
            "Precreating system.xml causes Jellyfin 10.11 to run code migrations before EF history exists"
        )
    }

    @Test
    fun `sso plugin configuration keeps keycloak group authorization`() {
        val config = repoFileText("stack.config/jellyfin/SSO-Auth.xml")

        assertTrue(config.contains("<OidClientId>jellyfin</OidClientId>"))
        assertTrue(config.contains("<RoleClaim>groups</RoleClaim>"))
        assertTrue(config.contains("<string>admins</string>"))
        assertTrue(config.contains("<string>users</string>"))
    }

    @Test
    fun `jellyfin reconciles configured stack admin users`() {
        val dockerfile = repoFileText("stack.config/jellyfin/Dockerfile")
        val compose = repoFileText("stack.compose/jellyfin.yml")
        val entrypoint = repoFileText("stack.config/jellyfin/entrypoint.sh")

        assertTrue(dockerfile.contains("sqlite3"))
        assertTrue(compose.contains("STACK_ADMIN_USER: \${STACK_ADMIN_USER}"))
        assertTrue(compose.contains("JELLYFIN_ADMIN_USERS: \${JELLYFIN_ADMIN_USERS:-}"))
        assertTrue(entrypoint.contains("promote_configured_admin_users()"))
        assertTrue(entrypoint.contains("configure_playback_policy()"))
        assertTrue(entrypoint.contains("INSERT INTO Permissions"))
        assertTrue(entrypoint.contains("WHERE Kind = 0"))
        assertTrue(entrypoint.contains("JELLYFIN_ENABLE_PLAYBACK_REMUXING:-false"))
        assertTrue(entrypoint.contains("WHERE Kind = 19"))
    }

    @Test
    fun `jellyfin transcode wrapper keeps generated video browser safe`() {
        val compose = repoFileText("stack.compose/jellyfin.yml")
        val entrypoint = repoFileText("stack.config/jellyfin/entrypoint.sh")
        val wrapper = repoFileText("stack.config/jellyfin/ffmpeg-websafe.sh")
        val dockerfile = repoFileText("stack.config/jellyfin/Dockerfile")

        assertFalse(compose.contains("JELLYFIN_DISABLE_BROWSER_HEVC_DIRECT_PLAY"))
        assertTrue(dockerfile.contains("ln -sf /usr/lib/jellyfin-ffmpeg/ffprobe /usr/local/bin/ffprobe"))
        assertTrue(compose.contains("JELLYFIN_TRANSCODE_H264_LEVEL: \${JELLYFIN_TRANSCODE_H264_LEVEL:-31}"))
        assertTrue(compose.contains("JELLYFIN_TRANSCODE_MAX_WIDTH: \${JELLYFIN_TRANSCODE_MAX_WIDTH:-1280}"))
        assertTrue(compose.contains("JELLYFIN_TRANSCODE_MAX_HEIGHT: \${JELLYFIN_TRANSCODE_MAX_HEIGHT:-720}"))
        assertFalse(entrypoint.contains("patch_web_client_codec_detection"))
        assertFalse(entrypoint.contains("webservices-force-compatible-transcode"))
        assertFalse(entrypoint.contains("preferFmp4HlsContainer"))
        assertFalse(entrypoint.contains("MediaSource.isTypeSupported"))
        assertFalse(entrypoint.contains("HTMLMediaElement.prototype.canPlayType"))
        assertTrue(wrapper.contains("JELLYFIN_TRANSCODE_H264_LEVEL:-31"))
        assertTrue(wrapper.contains("JELLYFIN_TRANSCODE_MAX_WIDTH:-1280"))
        assertTrue(wrapper.contains("JELLYFIN_TRANSCODE_MAX_HEIGHT:-720"))
        assertTrue(wrapper.contains("hevc_mp4toannexb"))
        assertTrue(wrapper.contains("hvc1"))
        assertTrue(wrapper.contains("out+=(\"libx264\")"))
        assertTrue(wrapper.contains("-copyts)"))
        assertTrue(wrapper.contains("out+=(\"make_zero\")"))
        assertTrue(wrapper.contains("force_key_frames"))
        assertTrue(wrapper.contains("n_forced*${'$'}{hls_time}"))
        assertTrue(wrapper.contains("browser_safe_filter"))
        assertTrue(wrapper.contains("needs_default_hls_transcode"))
        assertTrue(wrapper.contains("-map\" \"0:v:0"))
        assertTrue(wrapper.contains("-codec:a:0\" \"libfdk_aac"))
        assertTrue(wrapper.contains("websafe-ffmpeg"))
    }

    @Test
    fun `jellyfin native hls requests are normalized to h264 before proxying`() {
        val caddyfile = repoFileText("stack.config/caddy/Caddyfile")
        val compose = repoFileText("stack.compose/jellyfin.yml")
        val deploy = repoFileText("scripts/deploy.sh")
        val graph = repoFileText("stack.systemd/graph.json")
        val proxy = repoFileText("stack.containers/jellyfin-profile-proxy/jellyfin_profile_proxy.py")

        assertTrue(compose.contains("jellyfin-profile-proxy:"))
        assertTrue(compose.contains("dockerfile: ./stack.containers/jellyfin-profile-proxy/Dockerfile"))
        assertTrue(compose.contains("JELLYFIN_UPSTREAM_HOST: jellyfin"))
        assertTrue(graph.contains("\"jellyfin-profile-proxy\""))
        assertTrue(
            deploy.contains("webservices-jellyfin-profile-proxy.service"),
            "Deploy should reload the Jellyfin profile proxy when its image or bundled code changes"
        )
        assertTrue(caddyfile.contains("reverse_proxy jellyfin-profile-proxy:8080"))
        assertTrue(caddyfile.contains("header_up Host {host}"))
        assertTrue(caddyfile.contains("header_up X-Forwarded-Host {host}"))
        assertTrue(caddyfile.contains("header_up X-Forwarded-Proto {scheme}"))
        assertTrue(caddyfile.contains("@jellyfin_native_hls_hevc"))
        assertTrue(caddyfile.contains("path /videos/*/hls1/main/*"))
        assertTrue(caddyfile.contains("query VideoCodec=*hevc*"))
        assertTrue(caddyfile.contains("uri @jellyfin_native_hls_hevc query {"))
        assertTrue(caddyfile.contains("VideoCodec h264"))
        assertTrue(caddyfile.contains("RequireAvc true"))
        assertTrue(caddyfile.contains("TranscodeReasons VideoCodecNotSupported,AudioChannelsNotSupported"))
        assertTrue(caddyfile.contains("-hevc-level"))
        assertTrue(caddyfile.contains("-hevc-videobitdepth"))
        assertTrue(caddyfile.contains("-hevc-profile"))
        assertTrue(proxy.contains("maybe_rewrite_playback_info"))
        assertTrue(proxy.contains("\"/PlaybackInfo\""))
        assertTrue(proxy.contains("normalized HEVC from PlaybackInfo request"))
        assertTrue(proxy.contains("jellyfin"))
    }

    private fun repoFileText(relativePath: String): String =
        Files.readString(repoRoot().resolve(relativePath))

    private fun repoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("MODULE.bazel"))) {
                return current
            }
            current = current.parent ?: error("repo root not found")
        }
        error("repo root not found")
    }
}

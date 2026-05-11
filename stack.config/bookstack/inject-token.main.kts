#!/usr/bin/env kotlin

/**
 * Injects pre-generated BookStack API token into database.
 * Reads BOOKSTACK_API_TOKEN_ID and BOOKSTACK_API_TOKEN_SECRET from STACK_RUNTIME_ENV_FILE.
 */

import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

fun run(vararg cmd: String): String {
    val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
    val p = pb.start()
    val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
    val code = p.waitFor()
    if (code != 0) {
        println("[ERROR] Command failed: ${cmd.joinToString(" ")}")
        println(out)
        kotlin.system.exitProcess(1)
    }
    return out.trim()
}

fun detectRoot(): File {
    var current = File(".").canonicalFile
    while (true) {
        val repoRoot = current.resolve("stack.compose").isDirectory && current.resolve("scripts").isDirectory
        val distRoot = current.resolve("docker-compose.yml").isFile && current.resolve("runtime").isDirectory
        if (repoRoot || distRoot) return current
        current = current.parentFile ?: return File(".").canonicalFile
    }
}

fun expandHome(path: String): String {
    val trimmed = path.trim()
    if (trimmed == "~") return System.getProperty("user.home")
    if (trimmed.startsWith("~/")) return "${System.getProperty("user.home")}/${trimmed.removePrefix("~/")}"
    return trimmed
}

fun parseEnvFile(file: File): Map<String, String> =
    file.readLines()
        .filter { it.contains("=") && !it.trimStart().startsWith("#") }
        .associate {
            val parts = it.split("=", limit = 2)
            parts[0].trim() to parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
        }

fun resolveRuntimeEnvFile(root: File): File {
    val explicit = System.getenv("STACK_RUNTIME_ENV_FILE")?.trim()?.takeIf { it.isNotEmpty() }
    if (explicit != null) {
        return File(expandHome(explicit)).absoluteFile
    }
    val candidates = listOf(
        root.resolve("runtime/stack.env"),
        root.resolve("dist/runtime/stack.env")
    )
    return candidates.firstOrNull { it.exists() } ?: candidates.first()
}

fun resolveTokenExpiry(env: Map<String, String>): String {
    env["BOOKSTACK_API_TOKEN_EXPIRES_AT"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val ttlDays = env["BOOKSTACK_API_TOKEN_TTL_DAYS"]
        ?.toLongOrNull()
        ?.takeIf { it > 0 }
        ?: 90L
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    return Instant.now()
        .plus(Duration.ofDays(ttlDays))
        .atOffset(ZoneOffset.UTC)
        .format(formatter)
}

fun main() {
    val root = detectRoot()
    val envFile = resolveRuntimeEnvFile(root)
    if (!envFile.exists()) {
        println("[ERROR] Runtime env file not found: ${envFile.path}")
        kotlin.system.exitProcess(1)
    }

    val env = parseEnvFile(envFile)

    val tokenId = env["BOOKSTACK_API_TOKEN_ID"] ?: run {
        println("[ERROR] BOOKSTACK_API_TOKEN_ID not found in ${envFile.path}")
        kotlin.system.exitProcess(1)
    }

    val tokenSecret = env["BOOKSTACK_API_TOKEN_SECRET"] ?: run {
        println("[ERROR] BOOKSTACK_API_TOKEN_SECRET not found in ${envFile.path}")
        kotlin.system.exitProcess(1)
    }

    println("[INFO] Injecting BookStack API token...")
    println("[INFO] Token ID: $tokenId")
    val expiresAt = resolveTokenExpiry(env)
    println("[INFO] Token expires at: $expiresAt UTC")

    val ensureAutomationUserCmd = """
        ${'$'}roleId = \Illuminate\Support\Facades\DB::table('roles')->where('system_name', 'automation')->value('id');
        if (!${'$'}roleId) { throw new RuntimeException('BookStack automation role is missing; run 50-configure-permissions.sh first'); }
        ${'$'}userId = \Illuminate\Support\Facades\DB::table('users')->where('email', 'webservices-automation@localhost')->value('id');
        if (!${'$'}userId) {
            ${'$'}userId = \Illuminate\Support\Facades\DB::table('users')->insertGetId([
                'name' => 'webservices Automation',
                'email' => 'webservices-automation@localhost',
                'password' => '',
                'remember_token' => null,
                'created_at' => now(),
                'updated_at' => now(),
                'email_confirmed' => 1,
                'image_id' => 0,
                'external_auth_id' => '',
                'slug' => 'webservices-automation',
                'system_name' => null,
            ]);
        }
        \Illuminate\Support\Facades\DB::table('role_user')->insertOrIgnore(['user_id' => ${'$'}userId, 'role_id' => ${'$'}roleId]);
        echo ${'$'}userId;
    """.trimIndent().replace("\n", " ")

    val userId = run(
        "docker", "exec", "bookstack",
        "php", "/app/www/artisan", "tinker",
        "--execute=$ensureAutomationUserCmd"
    ).trim()

    println("[INFO] Using automation user ID: $userId")

    run(
        "docker", "exec", "bookstack",
        "php", "/app/www/artisan", "tinker",
        "--execute=BookStack\\Api\\ApiToken::where('name', 'webservices Automation')->delete();"
    )

    val createCmd = """
        ${'$'}user = BookStack\Users\Models\User::find($userId);
        ${'$'}token = new BookStack\Api\ApiToken();
        ${'$'}token->user_id = ${'$'}user->id;
        ${'$'}token->name = 'webservices Automation';
        ${'$'}token->token_id = '$tokenId';
        ${'$'}token->secret = \Illuminate\Support\Facades\Hash::make('$tokenSecret');
        ${'$'}token->expires_at = '$expiresAt';
        ${'$'}token->save();
        echo 'Token created successfully';
    """.trimIndent()

    val result = run(
        "docker", "exec", "bookstack",
        "php", "/app/www/artisan", "tinker",
        "--execute=$createCmd"
    )

    println("[SUCCESS] $result")
    println("[INFO] BookStack API token is now active")
}

main()

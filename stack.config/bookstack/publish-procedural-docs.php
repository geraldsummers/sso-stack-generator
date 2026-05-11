#!/usr/bin/env php
<?php
declare(strict_types=1);

function logLine(string $message): void
{
    fwrite(STDOUT, "[BookStack Procedural Docs] {$message}\n");
}

function envValue(string $key, string $default = ''): string
{
    $value = getenv($key);
    if ($value === false) {
        return $default;
    }

    $value = trim($value);
    return $value === '' ? $default : $value;
}

function esc(string $value): string
{
    return htmlspecialchars($value, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

function httpJsonRequest(
    string $baseUrl,
    string $tokenId,
    string $tokenSecret,
    string $method,
    string $path,
    ?array $payload = null
): array {
    $url = rtrim($baseUrl, '/') . $path;
    $headers = [
        'Authorization: Token ' . $tokenId . ':' . $tokenSecret,
        'Accept: application/json',
    ];

    $content = '';
    if ($payload !== null) {
        $content = json_encode($payload, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
        if ($content === false) {
            throw new RuntimeException("Unable to encode payload for {$method} {$path}");
        }
        $headers[] = 'Content-Type: application/json';
    }

    $context = stream_context_create([
        'http' => [
            'method' => $method,
            'header' => implode("\r\n", $headers),
            'content' => $content,
            'ignore_errors' => true,
            'timeout' => 45,
        ],
    ]);

    $body = @file_get_contents($url, false, $context);
    $responseHeaders = $http_response_header ?? [];
    $statusLine = $responseHeaders[0] ?? '';
    preg_match('/\s([0-9]{3})\s/', $statusLine, $matches);
    $status = isset($matches[1]) ? (int) $matches[1] : 0;

    if ($body === false) {
        $body = '';
    }

    if ($status < 200 || $status >= 300) {
        throw new RuntimeException("BookStack API {$method} {$path} failed with HTTP {$status}: {$body}");
    }

    if ($body === '') {
        return [];
    }

    $decoded = json_decode($body, true);
    if (!is_array($decoded)) {
        throw new RuntimeException("BookStack API {$method} {$path} returned invalid JSON");
    }

    return $decoded;
}

function fetchAll(string $baseUrl, string $tokenId, string $tokenSecret, string $path, int $perPage = 100): array
{
    $results = [];
    $page = 1;

    while (true) {
        $separator = str_contains($path, '?') ? '&' : '?';
        $response = httpJsonRequest(
            $baseUrl,
            $tokenId,
            $tokenSecret,
            'GET',
            $path . $separator . 'count=' . $perPage . '&page=' . $page
        );

        $items = $response['data'] ?? [];
        if (!is_array($items)) {
            throw new RuntimeException("BookStack API response for {$path} did not include a data array");
        }

        foreach ($items as $item) {
            if (is_array($item)) {
                $results[] = $item;
            }
        }

        if (count($items) < $perPage) {
            break;
        }

        $page++;
    }

    return $results;
}

function normalizedTagMap(array $tags): array
{
    $map = [];
    foreach ($tags as $tag) {
        if (!is_array($tag)) {
            continue;
        }
        $name = strtolower(trim((string) ($tag['name'] ?? '')));
        $value = trim((string) ($tag['value'] ?? ''));
        if ($name !== '') {
            $map[$name] = $value;
        }
    }
    return $map;
}

function firstNonEmptyTagValue(array $tags, array $names): ?string
{
    $map = normalizedTagMap($tags);
    foreach ($names as $name) {
        $key = strtolower($name);
        if (isset($map[$key])) {
            $value = trim((string) $map[$key]);
            if ($value !== '' && preg_match('~^https?://~i', $value) === 1) {
                return $value;
            }
        }
    }
    return null;
}

function pageUrl(string $publicBaseUrl, array $book, array $page): ?string
{
    $bookSlug = trim((string) ($book['slug'] ?? ''));
    $pageSlug = trim((string) ($page['slug'] ?? ''));
    if ($bookSlug === '' || $pageSlug === '') {
        return null;
    }

    return rtrim($publicBaseUrl, '/') . '/books/' . rawurlencode($bookSlug) . '/page/' . rawurlencode($pageSlug);
}

function isApiLikePage(array $page, array $tags, string $bodyHtml): ?string
{
    $title = strtolower(trim((string) ($page['name'] ?? '')));
    $description = strtolower(trim((string) ($page['description'] ?? '')));
    $bookName = strtolower(trim((string) ($page['book_name'] ?? '')));
    $tagBlob = strtolower(implode(' ', array_map(
        static fn (array $tag): string => trim((string) ($tag['name'] ?? '')) . ' ' . trim((string) ($tag['value'] ?? '')),
        array_filter($tags, static fn ($tag): bool => is_array($tag))
    )));
    $contentBlob = strtolower(trim(strip_tags($bodyHtml)));

    $haystack = implode(' ', [$title, $description, $bookName, $tagBlob, $contentBlob]);
    $signals = ['openapi', 'swagger', 'graphql', 'endpoint', 'rest', 'api', 'schema', 'route', 'procedure', 'reference'];

    foreach ($signals as $signal) {
        if (preg_match('/\b' . preg_quote($signal, '/') . '\b/i', $haystack) === 1) {
            return $signal;
        }
    }

    if (str_contains($haystack, '/api/')) {
        return '/api/';
    }

    return null;
}

function renderTable(array $headers, array $rows): string
{
    if ($rows === []) {
        return '<p><em>No matching pages were found yet.</em></p>';
    }

    $headCells = array_map(
        static fn (string $header): string => '<th style="text-align:left; padding:10px; border-bottom:1px solid #d8dde6; background:#f7f9fc;">' . esc($header) . '</th>',
        $headers
    );

    $bodyRows = [];
    foreach ($rows as $row) {
        $cells = [];
        foreach ($row as $cell) {
            $cells[] = '<td style="vertical-align:top; padding:10px; border-bottom:1px solid #edf1f5;">' . $cell . '</td>';
        }
        $bodyRows[] = '<tr>' . implode('', $cells) . '</tr>';
    }

    return
        '<table style="width:100%; border-collapse:collapse; font-size:0.95em;">' .
            '<thead><tr>' . implode('', $headCells) . '</tr></thead>' .
            '<tbody>' . implode('', $bodyRows) . '</tbody>' .
        '</table>';
}

function renderPageHtml(string $title, string $summary, array $headers, array $rows, string $bookstackPublicUrl, string $generatedAt, int $scannedPages, int $scannedBooks): string
{
    $table = renderTable($headers, $rows);
    $bookstackBase = rtrim($bookstackPublicUrl, '/');

    return
        '<h1>' . esc($title) . '</h1>' .
        '<div style="margin-bottom:20px; padding:14px; background:#f7f9fc; border-left:4px solid #2563eb;">' .
            '<p><strong>Generated at:</strong> ' . esc($generatedAt) . ' UTC</p>' .
            '<p><strong>BookStack base:</strong> <a href="' . esc($bookstackBase) . '" target="_blank" rel="noreferrer noopener">' . esc($bookstackBase) . '</a></p>' .
            '<p><strong>Scanned books:</strong> ' . esc((string) $scannedBooks) . ' | <strong>Scanned pages:</strong> ' . esc((string) $scannedPages) . '</p>' .
        '</div>' .
        '<p>' . esc($summary) . '</p>' .
        '<div style="margin-top:20px;">' . $table . '</div>' .
        '<hr>' .
        '<p style="font-size:0.9em; color:#666;">' .
            '<em>This page is runtime-generated from live BookStack content published by the ingestion pipeline.</em>' .
        '</p>';
}

function ensureBook(
    string $baseUrl,
    string $tokenId,
    string $tokenSecret,
    string $bookName,
    string $bookDescription,
    array $booksByName
): array {
    $existing = $booksByName[$bookName] ?? null;
    if (is_array($existing)) {
        $bookId = (int) ($existing['id'] ?? 0);
        if ($bookId <= 0) {
            throw new RuntimeException("Existing BookStack book '{$bookName}' is missing an id");
        }

        $existingDescription = trim((string) ($existing['description'] ?? ''));
        if ($existingDescription === $bookDescription) {
            return $existing;
        }

        return httpJsonRequest(
            $baseUrl,
            $tokenId,
            $tokenSecret,
            'PUT',
            '/api/books/' . $bookId,
            [
                'name' => $bookName,
                'description' => $bookDescription,
            ]
        );
    }

    return httpJsonRequest(
        $baseUrl,
        $tokenId,
        $tokenSecret,
        'POST',
        '/api/books',
        [
            'name' => $bookName,
            'description' => $bookDescription,
        ]
    );
}

function ensurePage(
    string $baseUrl,
    string $tokenId,
    string $tokenSecret,
    array $existingPagesByTitle,
    int $bookId,
    string $pageTitle,
    string $html,
    array $tags
): array {
    $payload = [
        'book_id' => $bookId,
        'name' => $pageTitle,
        'html' => $html,
        'tags' => array_map(
            static fn (array $tag): array => [
                'name' => (string) ($tag['name'] ?? ''),
                'value' => (string) ($tag['value'] ?? ''),
            ],
            $tags
        ),
    ];

    $existing = $existingPagesByTitle[$pageTitle] ?? null;
    if (is_array($existing)) {
        $pageId = (int) ($existing['id'] ?? 0);
        if ($pageId <= 0) {
            throw new RuntimeException("Existing BookStack page '{$pageTitle}' is missing an id");
        }

        try {
            $currentPage = httpJsonRequest(
                $baseUrl,
                $tokenId,
                $tokenSecret,
                'GET',
                '/api/pages/' . $pageId
            );
            $currentHtml = (string) ($currentPage['html'] ?? '');
            if ($currentHtml === $html) {
                return $currentPage;
            }
        } catch (Throwable $pageError) {
            logLine(sprintf(
                'WARN: Could not read current procedural page %s (%d): %s',
                $pageTitle,
                $pageId,
                $pageError->getMessage()
            ));
        }

        return httpJsonRequest(
            $baseUrl,
            $tokenId,
            $tokenSecret,
            'PUT',
            '/api/pages/' . $pageId,
            $payload
        );
    }

    return httpJsonRequest(
        $baseUrl,
        $tokenId,
        $tokenSecret,
        'POST',
        '/api/pages',
        $payload
    );
}

try {
    $bookstackUrl = envValue('BOOKSTACK_URL', 'http://bookstack:80');
    $bookstackPublicUrl = envValue('BOOKSTACK_PUBLIC_URL', $bookstackUrl);
    $tokenId = envValue('BOOKSTACK_API_TOKEN_ID');
    $tokenSecret = envValue('BOOKSTACK_API_TOKEN_SECRET');
    $bookName = envValue('BOOKSTACK_PROCEDURAL_DOCS_BOOK', 'Procedural Docs');
    $bookDescription = envValue(
        'BOOKSTACK_PROCEDURAL_DOCS_DESCRIPTION',
        'Runtime-generated procedural documentation indexes derived from live BookStack content.'
    );

    if ($tokenId === '' || $tokenSecret === '') {
        logLine('ERROR: BOOKSTACK_API_TOKEN_ID and BOOKSTACK_API_TOKEN_SECRET must be set');
        exit(0);
    }

    logLine('Refreshing procedural docs indexes...');

    $bookList = fetchAll($bookstackUrl, $tokenId, $tokenSecret, '/api/books');
    $booksById = [];
    $booksByName = [];
    foreach ($bookList as $book) {
        $bookId = (int) ($book['id'] ?? 0);
        $name = trim((string) ($book['name'] ?? ''));
        if ($bookId > 0 && $name !== '') {
            $booksById[$bookId] = $book;
            $booksByName[$name] = $book;
        }
    }

    $proceduralBook = ensureBook($bookstackUrl, $tokenId, $tokenSecret, $bookName, $bookDescription, $booksByName);
    $proceduralBookId = (int) ($proceduralBook['id'] ?? 0);
    if ($proceduralBookId <= 0) {
        throw new RuntimeException("Procedural docs book '{$bookName}' was not created or updated correctly");
    }

    $pageList = fetchAll($bookstackUrl, $tokenId, $tokenSecret, '/api/pages');
    $existingIndexPages = [];
    foreach ($pageList as $pageSummary) {
        $pageTitle = trim((string) ($pageSummary['name'] ?? ''));
        $pageBookId = (int) ($pageSummary['book_id'] ?? 0);
        if ($pageBookId === $proceduralBookId && $pageTitle !== '') {
            $existingIndexPages[$pageTitle] = $pageSummary;
        }
    }

    $urlEntries = [];
    $apiEntries = [];
    $scannedPages = 0;

    foreach ($pageList as $pageSummary) {
        $pageBookId = (int) ($pageSummary['book_id'] ?? 0);
        if ($pageBookId === $proceduralBookId) {
            continue;
        }

        $pageId = (int) ($pageSummary['id'] ?? 0);
        if ($pageId <= 0) {
            continue;
        }

        $pageBook = $booksById[$pageBookId] ?? null;
        if (!is_array($pageBook)) {
            continue;
        }

        try {
            $detail = httpJsonRequest($bookstackUrl, $tokenId, $tokenSecret, 'GET', '/api/pages/' . $pageId);
        } catch (Throwable $pageError) {
            logLine(sprintf(
                'WARN: Skipping page %s (%d): %s',
                $pageSummary['name'] ?? 'unknown',
                $pageId,
                $pageError->getMessage()
            ));
            continue;
        }
        $tags = array_values(array_filter($detail['tags'] ?? [], static fn ($tag): bool => is_array($tag)));
        $bodyHtml = (string) ($detail['html'] ?? '');
        $pageBookName = trim((string) ($pageBook['name'] ?? ''));
        $pageData = [
            'id' => $pageId,
            'name' => trim((string) ($detail['name'] ?? ($pageSummary['name'] ?? ''))),
            'slug' => trim((string) ($detail['slug'] ?? ($pageSummary['slug'] ?? ''))),
            'book_name' => $pageBookName,
            'book' => $pageBook,
            'tags' => $tags,
            'html' => $bodyHtml,
        ];

        $pageBookSlug = trim((string) ($pageBook['slug'] ?? ''));
        $bookstackPageUrl = $pageBookSlug !== '' && $pageData['slug'] !== ''
            ? pageUrl($bookstackPublicUrl, $pageBook, $pageData)
            : null;

        $externalUrls = [];
        foreach (['presentation_url', 'url', 'source_url', 'link', 'homepage'] as $candidateKey) {
            $candidateUrl = firstNonEmptyTagValue($tags, [$candidateKey]);
            if ($candidateUrl !== null) {
                $externalUrls[] = $candidateUrl;
            }
        }
        $externalUrls = array_values(array_unique($externalUrls));

        foreach ($externalUrls as $externalUrl) {
            $urlEntries[] = [
                'book' => $pageBookName,
                'page' => $pageData['name'],
                'bookstack_url' => $bookstackPageUrl ?? '',
                'external_url' => $externalUrl,
                'tags' => $tags,
            ];
        }

        $apiSignal = isApiLikePage($pageData, $tags, $bodyHtml);
        if ($apiSignal !== null) {
            $apiEntries[] = [
                'book' => $pageBookName,
                'page' => $pageData['name'],
                'bookstack_url' => $bookstackPageUrl ?? '',
                'signal' => $apiSignal,
                'tags' => $tags,
            ];
        }

        $scannedPages++;
    }

    usort($urlEntries, static function (array $left, array $right): int {
        return [$left['book'], $left['page'], $left['external_url']] <=> [$right['book'], $right['page'], $right['external_url']];
    });

    usort($apiEntries, static function (array $left, array $right): int {
        return [$left['book'], $left['page'], $left['signal']] <=> [$right['book'], $right['page'], $right['signal']];
    });

    $generatedAt = gmdate('Y-m-d H:i:s');
    $scannedBooks = count($booksById);

    $urlRows = [];
    foreach ($urlEntries as $entry) {
        $tagSummary = [];
        foreach ($entry['tags'] as $tag) {
            $name = trim((string) ($tag['name'] ?? ''));
            $value = trim((string) ($tag['value'] ?? ''));
            if ($name !== '' && $value !== '') {
                $tagSummary[] = $name . '=' . $value;
            }
        }

        $urlRows[] = [
            '<strong>' . esc($entry['book']) . '</strong>',
            esc($entry['page']),
            $entry['bookstack_url'] !== '' ? '<a href="' . esc($entry['bookstack_url']) . '" target="_blank" rel="noreferrer noopener">Open</a>' : '<em>Unavailable</em>',
            '<a href="' . esc($entry['external_url']) . '" target="_blank" rel="noreferrer noopener">' . esc($entry['external_url']) . '</a>',
            esc(implode(', ', array_slice($tagSummary, 0, 6))),
        ];
    }

    $apiRows = [];
    foreach ($apiEntries as $entry) {
        $tagSummary = [];
        foreach ($entry['tags'] as $tag) {
            $name = trim((string) ($tag['name'] ?? ''));
            $value = trim((string) ($tag['value'] ?? ''));
            if ($name !== '' && $value !== '') {
                $tagSummary[] = $name . '=' . $value;
            }
        }

        $apiRows[] = [
            '<strong>' . esc($entry['book']) . '</strong>',
            esc($entry['page']),
            $entry['bookstack_url'] !== '' ? '<a href="' . esc($entry['bookstack_url']) . '" target="_blank" rel="noreferrer noopener">Open</a>' : '<em>Unavailable</em>',
            esc($entry['signal']),
            esc(implode(', ', array_slice($tagSummary, 0, 6))),
        ];
    }

    $urlHtml = renderPageHtml(
        'URL Index',
        'Runtime-generated index of pages with external or presentation URLs discovered from BookStack tags.',
        ['Book', 'Page', 'BookStack', 'External URL', 'Tags'],
        $urlRows,
        $bookstackPublicUrl,
        $generatedAt,
        $scannedPages,
        $scannedBooks
    );

    $apiHtml = renderPageHtml(
        'API Index',
        'Runtime-generated index of pages that look API-, reference-, or procedure-oriented based on live BookStack titles, tags, and body text.',
        ['Book', 'Page', 'BookStack', 'Signal', 'Tags'],
        $apiRows,
        $bookstackPublicUrl,
        $generatedAt,
        $scannedPages,
        $scannedBooks
    );

    $urlPage = ensurePage(
        $bookstackUrl,
        $tokenId,
        $tokenSecret,
        $existingIndexPages,
        $proceduralBookId,
        'URL Index',
        $urlHtml,
        [
            ['name' => 'generated_by', 'value' => 'bookstack-procedural-docs'],
            ['name' => 'index_type', 'value' => 'url'],
            ['name' => 'source', 'value' => 'bookstack'],
        ]
    );

    $apiPage = ensurePage(
        $bookstackUrl,
        $tokenId,
        $tokenSecret,
        $existingIndexPages,
        $proceduralBookId,
        'API Index',
        $apiHtml,
        [
            ['name' => 'generated_by', 'value' => 'bookstack-procedural-docs'],
            ['name' => 'index_type', 'value' => 'api'],
            ['name' => 'source', 'value' => 'bookstack'],
        ]
    );

    logLine(sprintf(
        'Updated %s book with %d scanned pages across %d books (%d URL rows, %d API rows).',
        $bookName,
        $scannedPages,
        $scannedBooks,
        count($urlRows),
        count($apiRows)
    ));

    if (is_array($urlPage) && is_array($apiPage)) {
        $urlPageUrl = pageUrl($bookstackPublicUrl, $proceduralBook, $urlPage) ?? 'unavailable';
        $apiPageUrl = pageUrl($bookstackPublicUrl, $proceduralBook, $apiPage) ?? 'unavailable';
        logLine('URL Index: ' . $urlPageUrl);
        logLine('API Index: ' . $apiPageUrl);
    }

    logLine('Refresh complete. Next poll is controlled by the sidecar loop.');
} catch (Throwable $e) {
    logLine('ERROR: ' . $e->getMessage());
    exit(0);
}

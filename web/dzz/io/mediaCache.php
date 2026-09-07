<?php

if (!defined('IN_OAOOA')) {
    exit('Access Denied');
}

/**
 * Stable media URLs deliberately use the resource id instead of an expiring
 * Pencode/Decode payload. Access is still checked on every cache miss.
 */
function pichome_media_normalize_rid($rid)
{
    $rid = trim((string)$rid);
    if ($rid === '' || strlen($rid) > 256 || !preg_match('/^[A-Za-z0-9_-]+$/', $rid)) {
        return '';
    }
    return $rid;
}

function pichome_media_fetch_authorized_resource($rid)
{
    $rid = pichome_media_normalize_rid($rid);
    if ($rid === '') {
        return false;
    }

    $resource = C::t('pichome_resources')->fetch($rid);
    if (!$resource || empty($resource['appid'])) {
        return false;
    }
    $app = C::t('pichome_vapp')->fetch($resource['appid']);
    if (!$app || !empty($app['isdelete'])) {
        return false;
    }
    $isLibraryMember = C::t('pichome_vappmember')->checkuserperm_by_appid($resource['appid']);
    $canViewLibrary = C::t('pichome_vapp')->getpermbypermdata($app['view'], $resource['appid']);
    if (!$isLibraryMember && !$canViewLibrary) {
        return false;
    }

    $userLevel = intval(getglobal('pichomelevel'));
    if (!empty($resource['level']) && $userLevel < intval($resource['level'])) {
        return false;
    }
    return $resource;
}

/**
 * Keep this cheap: database revision fields are sufficient to invalidate the
 * browser URL without reading or hashing a potentially huge media file.
 */
function pichome_media_revision($resource, $variant = 'original')
{
    $mediaMtime = isset($resource['_media_mtime'])
        ? $resource['_media_mtime']
        : (isset($resource['mtime']) ? $resource['mtime'] : 0);
    $parts = array(
        isset($resource['rid']) ? $resource['rid'] : '',
        isset($resource['size']) ? $resource['size'] : 0,
        $mediaMtime,
        isset($resource['hasthumb']) ? $resource['hasthumb'] : 0,
        isset($resource['thumbdotime']) ? $resource['thumbdotime'] : 0,
        (string)$variant,
    );
    return substr(sha1(implode(':', $parts)), 0, 16);
}

function pichome_media_stable_url($resource, $operation, $variant = 'original')
{
    $rid = pichome_media_normalize_rid(isset($resource['rid']) ? $resource['rid'] : '');
    if ($rid === '' || !in_array($operation, array('getImg', 'getStream'), true)) {
        return '';
    }

    $query = array(
        'mod' => 'io',
        'op' => $operation,
        'rid' => $rid,
        'pichome_rid' => $rid,
        'v' => pichome_media_revision($resource, $variant),
    );
    if ($operation === 'getImg') {
        $query['variant'] = ($variant === 'large') ? 'large' : 'small';
    }
    return getglobal('siteurl') . 'index.php?' . http_build_query($query, '', '&');
}

/**
 * Build a cheap validator without hashing the entire media file.
 * The URL identifies the resource; size, mtime and ctime identify its revision.
 */
function pichome_media_cache_etag($filename, $size, $mtime)
{
    $ctime = @filectime($filename);
    return '"' . sha1($size . ':' . $mtime . ':' . intval($ctime)) . '"';
}

function pichome_media_send_cache_headers($etag, $mtime, $maxAge = 2592000)
{
    header('Vary: Cookie', false);
    header('Cache-Control: private, max-age=' . intval($maxAge));
    header('Expires: ' . gmdate('D, d M Y H:i:s', time() + intval($maxAge)) . ' GMT');
    header('ETag: ' . $etag);
    header('Last-Modified: ' . gmdate('D, d M Y H:i:s', $mtime) . ' GMT');
}

/**
 * If-None-Match takes precedence over If-Modified-Since for GET and HEAD.
 */
function pichome_media_cache_is_not_modified($etag, $mtime)
{
    $method = isset($_SERVER['REQUEST_METHOD']) ? strtoupper($_SERVER['REQUEST_METHOD']) : 'GET';
    if ($method !== 'GET' && $method !== 'HEAD') {
        return false;
    }

    if (isset($_SERVER['HTTP_IF_NONE_MATCH'])) {
        $candidates = explode(',', $_SERVER['HTTP_IF_NONE_MATCH']);
        foreach ($candidates as $candidate) {
            $candidate = trim($candidate);
            if ($candidate === '*') {
                return true;
            }
            if (stripos($candidate, 'W/') === 0) {
                $candidate = trim(substr($candidate, 2));
            }
            if ($candidate === $etag) {
                return true;
            }
        }
        return false;
    }

    if (!empty($_SERVER['HTTP_IF_MODIFIED_SINCE'])) {
        $value = explode(';', $_SERVER['HTTP_IF_MODIFIED_SINCE'], 2);
        $timestamp = strtotime(trim($value[0]));
        return $timestamp !== false && $mtime <= $timestamp;
    }

    return false;
}

/**
 * A stale If-Range means the Range header must be ignored and a full 200 sent.
 */
function pichome_media_cache_if_range_matches($etag, $mtime)
{
    if (empty($_SERVER['HTTP_IF_RANGE'])) {
        return true;
    }

    $ifRange = trim($_SERVER['HTTP_IF_RANGE']);
    if ($ifRange === '' || stripos($ifRange, 'W/') === 0) {
        return false;
    }
    if ($ifRange[0] === '"') {
        return $ifRange === $etag;
    }

    $timestamp = strtotime($ifRange);
    return $timestamp !== false && $mtime <= $timestamp;
}

/**
 * Parse one RFC 7233 byte range. Multiple ranges are intentionally rejected.
 * Returns array(start, end), or false when the request cannot be satisfied.
 */
function pichome_media_parse_range($rangeHeader, $total)
{
    if ($total <= 0 || !preg_match('/^bytes=(\d*)-(\d*)$/i', trim($rangeHeader), $matches)) {
        return false;
    }

    if ($matches[1] === '' && $matches[2] === '') {
        return false;
    }

    if ($matches[1] === '') {
        $suffixLength = intval($matches[2]);
        if ($suffixLength <= 0) {
            return false;
        }
        $start = max(0, $total - $suffixLength);
        $end = $total - 1;
    } else {
        $start = intval($matches[1]);
        $end = ($matches[2] === '') ? ($total - 1) : intval($matches[2]);
        if ($start >= $total || $end < $start) {
            return false;
        }
        $end = min($end, $total - 1);
    }

    return array($start, $end);
}

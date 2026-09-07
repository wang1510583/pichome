<?php
if (!defined('IN_OAOOA')) {
    exit('Access Denied');
}

/**
 * Read-only, revisioned video frame atlases for the Android detail viewer.
 *
 * Each atlas covers a short profile-defined interval. The manifest supplies the
 * fixed sampling rate and cell geometry, while the image endpoint lazily creates
 * an immutable WebP tile.
 */
function pichome_frame_atlas_json($payload, $status = 200) {
    if (!headers_sent()) {
        http_response_code($status);
        header('Content-Type: application/json; charset=utf-8');
        header('Cache-Control: no-store');
    }
    exit(json_encode($payload));
}

function pichome_frame_atlas_error($code, $message, $status = 400) {
    pichome_frame_atlas_json(array(
        'success' => false,
        'code' => $code,
        'message' => $message
    ), $status);
}

function pichome_frame_atlas_binary($identifier) {
    $app = C::t('app_market')->fetch_by_identifier('ffmpeg', 'dzz');
    $extra = $app && $app['extra'] ? @unserialize($app['extra']) : array();
    $key = $identifier.'.binaries';
    if (!empty($extra[$key])) {
        return $extra[$key];
    }
    if (strstr(PHP_OS, 'WIN')) {
        return DZZ_ROOT.'dzz\\ffmpeg\\ffmpeg\\'.$identifier.'.exe';
    }
    return '/usr/bin/'.$identifier;
}

function pichome_frame_atlas_source($rid, $resourcesdata, $appdata) {
    $attrdata = C::t('pichome_resources_attr')->fetch($rid);
    $relativePath = isset($attrdata['path']) ? (string)$attrdata['path'] : '';
    $inputFile = '';
    if (is_numeric($relativePath)) {
        $inputFile = IO::getStream('attach::'.intval($relativePath));
    } elseif ($relativePath !== '' && $appdata) {
        $rawAppPath = (string)$appdata['path'];
        if (strpos($rawAppPath, ':') === false) {
            $appRoot = realpath($rawAppPath);
            $candidate = rtrim($rawAppPath, '/\\').BS.ltrim(
                str_replace(array('/', '\\'), BS, $relativePath),
                '/\\'
            );
            $inputFile = realpath($candidate);
            if (!$appRoot
                || !$inputFile
                || ($inputFile !== $appRoot
                    && strpos($inputFile, $appRoot.DIRECTORY_SEPARATOR) !== 0)) {
                $inputFile = '';
            }
        } else {
            $inputFile = IO::getStream(
                rtrim($rawAppPath, '/\\').'/'.ltrim($relativePath, '/\\')
            );
        }
    }
    if (!$inputFile || !is_file($inputFile) || !is_readable($inputFile)) {
        return false;
    }
    return array(
        'file' => $inputFile,
        'relativePath' => $relativePath,
        'attrdata' => $attrdata
    );
}

function pichome_frame_atlas_rate($value) {
    if (!$value) return 0;
    $parts = explode('/', (string)$value, 2);
    if (count($parts) === 2) {
        $denominator = floatval($parts[1]);
        return $denominator > 0 ? floatval($parts[0]) / $denominator : 0;
    }
    return floatval($value);
}

function pichome_frame_atlas_profile_config($profile) {
    switch ((string)$profile) {
        case 'desktop-balanced':
            return array(
                'name' => 'desktop-balanced',
                'version' => 6,
                'cacheVersion' => 'frame-atlas-v6-desktop-balanced',
                'revisionSeed' => 'frame-atlas-v6-desktop-balanced-1440-960-q88-quarter-grid4096-mem40',
                'longEdgeLowFps' => 1440,
                'longEdgeHighFps' => 960,
                'quality' => 88,
                'segmentSeconds' => 0.25,
                'maxDecodedBytes' => 40 * 1024 * 1024
            );
        case 'desktop-ultra':
            return array(
                'name' => 'desktop-ultra',
                'version' => 5,
                'cacheVersion' => 'frame-atlas-v5-desktop-ultra',
                'revisionSeed' => 'frame-atlas-v5-desktop-ultra-1920-1280-q92-quarter-grid4096-mem64',
                'longEdgeLowFps' => 1920,
                'longEdgeHighFps' => 1280,
                'quality' => 92,
                'segmentSeconds' => 0.25,
                'maxDecodedBytes' => 64 * 1024 * 1024
            );
        case 'desktop-hq':
            return array(
                'name' => 'desktop-hq',
                'version' => 4,
                'cacheVersion' => 'frame-atlas-v4-desktop-hq',
                'revisionSeed' => 'frame-atlas-v4-desktop-hq-720-512-q90-grid4096-mem64',
                'longEdgeLowFps' => 720,
                'longEdgeHighFps' => 512,
                'quality' => 90,
                'segmentSeconds' => 1,
                'maxDecodedBytes' => 64 * 1024 * 1024
            );
        case 'mobile-aligned':
        case 'mobile-ultra':
            return array(
                'name' => (string)$profile,
                'version' => $profile === 'mobile-aligned' ? 5 : 4,
                'cacheVersion' => $profile === 'mobile-aligned' ? 'frame-atlas-v5-mobile-aligned' : 'frame-atlas-v4-mobile-ultra',
                'revisionSeed' => 'frame-atlas-' . $profile . '-640-480-q90-grid4096-mem36',
                'longEdgeLowFps' => 640,
                'longEdgeHighFps' => 480,
                'quality' => 90,
                'segmentSeconds' => 1,
                'maxDecodedBytes' => 36 * 1024 * 1024
            );
        case 'mobile-hq':
            return array(
                'name' => 'mobile-hq',
                'version' => 3,
                'cacheVersion' => 'frame-atlas-v3',
                'revisionSeed' => 'frame-atlas-v3-mobile-hq',
                'longEdgeLowFps' => 480,
                'longEdgeHighFps' => 384,
                'quality' => 85,
                'segmentSeconds' => 1,
                'maxDecodedBytes' => 0
            );
        default:
            return array(
                'name' => 'default',
                'version' => 2,
                'cacheVersion' => 'frame-atlas-v2',
                'revisionSeed' => 'frame-atlas-v2',
                'longEdgeLowFps' => 320,
                'longEdgeHighFps' => 320,
                'quality' => 80,
                'segmentSeconds' => 1,
                'maxDecodedBytes' => 0
            );
    }
}

function pichome_frame_atlas_grid($frameCount, $cellWidth, $cellHeight) {
    $textureLimit = 4096;
    $frameCount = max(1, intval($frameCount));
    $maxColumns = max(1, intval(floor($textureLimit / max(1, $cellWidth))));
    $maxRows = max(1, intval(floor($textureLimit / max(1, $cellHeight))));
    $minimumColumns = max(1, intval(ceil($frameCount / $maxRows)));
    if ($minimumColumns > $maxColumns) return false;
    $columns = min($frameCount, max($minimumColumns, min(8, $maxColumns)));
    $rows = intval(ceil($frameCount / $columns));
    if ($columns * $cellWidth > $textureLimit || $rows * $cellHeight > $textureLimit) {
        return false;
    }
    return array('columns' => $columns, 'rows' => $rows);
}

function pichome_frame_atlas_geometry(
    $width,
    $height,
    $frameCount,
    $desiredLongEdge,
    $maxDecodedBytes = 0
) {
    $minimumLongEdge = min(320, max(2, intval($desiredLongEdge)));
    for ($longEdge = max(2, intval($desiredLongEdge)); $longEdge >= $minimumLongEdge; $longEdge -= 2) {
        if ($width >= $height) {
            $cellWidth = $longEdge;
            $cellHeight = max(2, intval(round($height * $longEdge / $width)));
        } else {
            $cellHeight = $longEdge;
            $cellWidth = max(2, intval(round($width * $longEdge / $height)));
        }
        // Even cell sizes are friendlier to FFmpeg/WebP encoders.
        if ($cellWidth % 2) $cellWidth++;
        if ($cellHeight % 2) $cellHeight++;
        $grid = pichome_frame_atlas_grid($frameCount, $cellWidth, $cellHeight);
        $decodedBytes = $grid
            ? $grid['columns'] * $cellWidth * $grid['rows'] * $cellHeight * 4
            : 0;
        if ($grid && (!$maxDecodedBytes || $decodedBytes <= $maxDecodedBytes)) {
            return array(
                'cellWidth' => $cellWidth,
                'cellHeight' => $cellHeight,
                'columns' => $grid['columns'],
                'rows' => $grid['rows']
            );
        }
    }
    return false;
}

function pichome_frame_atlas_cache_dir($rid, $profile = 'default') {
    $profileConfig = pichome_frame_atlas_profile_config($profile);
    $directory = rtrim(getglobal('setting/attachdir'), '/\\')
        .BS.'cache'.BS.$profileConfig['cacheVersion'].BS.hash('sha256', (string)$rid).BS;
    dmkdir($directory);
    return $directory;
}

function pichome_frame_atlas_probe($rid, $resourcesdata, $source, $profile = 'default') {
    $file = $source['file'];
    $size = @filesize($file);
    $mtime = @filemtime($file);
    $profileConfig = pichome_frame_atlas_profile_config($profile);
    $profile = $profileConfig['name'];
    $revision = substr(hash('sha256', implode('|', array(
        $profileConfig['revisionSeed'] . '-aligned-grid-v2',
        (string)$rid,
        (string)$source['relativePath'],
        (string)$size,
        (string)$mtime,
        isset($resourcesdata['md5']) ? (string)$resourcesdata['md5'] : '',
        isset($resourcesdata['mtime']) ? (string)$resourcesdata['mtime'] : ''
    ))), 0, 24);
    $directory = pichome_frame_atlas_cache_dir($rid, $profile);
    $metadataFile = $directory.'manifest-'.$revision.'.json';
    if (is_file($metadataFile)) {
        $cached = json_decode(@file_get_contents($metadataFile), true);
        if (is_array($cached) && !empty($cached['success'])) {
            return $cached;
        }
    }

    $ffprobe = pichome_frame_atlas_binary('ffprobe');
    if (!is_file($ffprobe) || !is_executable($ffprobe)) {
        pichome_frame_atlas_error('ffprobe_unavailable', '服务器未安装可用的 FFprobe', 503);
    }
    $command = (!strstr(PHP_OS, 'WIN') && is_executable('/usr/bin/timeout')
            ? escapeshellarg('/usr/bin/timeout').' -k 3s 15s '
            : '')
        .escapeshellarg($ffprobe)
        .' -v error -select_streams v:0'
        .' -show_entries '.escapeshellarg('stream=width,height,avg_frame_rate,r_frame_rate,duration:format=duration')
        .' -of json '.escapeshellarg($file);
    $output = array();
    $status = 1;
    @exec($command.' 2>&1', $output, $status);
    $probe = $status === 0 ? json_decode(implode("\n", $output), true) : false;
    $stream = $probe && !empty($probe['streams'][0]) ? $probe['streams'][0] : array();
    $width = isset($stream['width']) ? intval($stream['width']) : 0;
    $height = isset($stream['height']) ? intval($stream['height']) : 0;
    $duration = isset($stream['duration']) ? floatval($stream['duration']) : 0;
    if ($duration <= 0 && isset($probe['format']['duration'])) {
        $duration = floatval($probe['format']['duration']);
    }
    if ($duration <= 0 && !empty($source['attrdata']['duration'])) {
        $duration = floatval($source['attrdata']['duration']);
    }
    $fps = pichome_frame_atlas_rate(isset($stream['avg_frame_rate']) ? $stream['avg_frame_rate'] : '');
    if ($fps < 1) {
        $fps = pichome_frame_atlas_rate(isset($stream['r_frame_rate']) ? $stream['r_frame_rate'] : '');
    }
    if ($width < 1 || $height < 1 || $duration <= 0 || $fps < 1) {
        runlog('frame_atlas', 'FFprobe failed for rid '.$rid.': '.implode(' ', array_slice($output, -3)));
        pichome_frame_atlas_error('probe_failed', '无法读取视频帧信息', 422);
    }

    // Cap sampling at 60 fps so one segment remains practical to generate and decode.
    $fps = min(60, max(1, $fps));
    $nearestIntegerRate = round($fps);
    if (abs($fps - $nearestIntegerRate) < 0.01) {
        $fps = $nearestIntegerRate;
    }
    $fps = round($fps, 6);
    $segmentSeconds = max(0.05, floatval($profileConfig['segmentSeconds']));
    $framesPerSegment = max(1, intval(ceil($fps * $segmentSeconds)));
    if (strpos($profile, 'desktop-') === 0 || $profile === 'mobile-aligned') {
        $segmentSeconds = $framesPerSegment / $fps;
    }
    $desiredLongEdge = $fps > 30.5
        ? $profileConfig['longEdgeHighFps']
        : $profileConfig['longEdgeLowFps'];
    $geometry = pichome_frame_atlas_geometry(
        $width,
        $height,
        $framesPerSegment,
        $desiredLongEdge,
        $profileConfig['maxDecodedBytes']
    );
    if (!$geometry) {
        pichome_frame_atlas_error('atlas_geometry_failed', '无法生成兼容的帧图集尺寸', 422);
    }
    $metadata = array(
        'success' => true,
        'version' => $profileConfig['version'],
        'profile' => $profile,
        'rid' => (string)$rid,
        'revision' => $revision,
        'duration' => round($duration, 6),
        'fps' => $fps,
        'sourceWidth' => $width,
        'sourceHeight' => $height,
        'cellWidth' => $geometry['cellWidth'],
        'cellHeight' => $geometry['cellHeight'],
        'columns' => $geometry['columns'],
        'rows' => $geometry['rows'],
        'framesPerSegment' => $framesPerSegment,
        'segmentSeconds' => $segmentSeconds,
        'maxSegment' => max(0, intval(ceil($duration / $segmentSeconds)) - 1)
    );
    $temporary = $metadataFile.'.'.strtolower(random(8)).'.tmp';
    if (@file_put_contents($temporary, json_encode($metadata), LOCK_EX) !== false) {
        @chmod($temporary, 0664);
        @rename($temporary, $metadataFile);
    } else {
        @unlink($temporary);
    }
    return $metadata;
}

function pichome_frame_atlas_serve_manifest($metadata) {
    pichome_frame_atlas_json($metadata);
}

function pichome_frame_atlas_serve_image($rid, $source, $metadata, $profile = 'default') {
    $profileConfig = pichome_frame_atlas_profile_config($profile);
    $revision = isset($_GET['revision']) ? trim($_GET['revision']) : '';
    if (!$revision || !hash_equals($metadata['revision'], $revision)) {
        pichome_frame_atlas_error('revision_changed', '视频版本已变化，请重新获取帧图集信息', 409);
    }
    $second = isset($_GET['second']) ? intval($_GET['second']) : -1;
    if ($second < 0 || $second > $metadata['maxSegment']) {
        pichome_frame_atlas_error('invalid_segment', '帧图集分段超出视频范围', 416);
    }
    $segmentSeconds = !empty($metadata['segmentSeconds'])
        ? max(0.05, floatval($metadata['segmentSeconds']))
        : 1;
    $segmentStart = $second * $segmentSeconds;
    $segmentDuration = min(
        $segmentSeconds,
        max(0, $metadata['duration'] - $segmentStart)
    );
    $frameCount = max(1, min(
        $metadata['framesPerSegment'],
        intval(ceil($segmentDuration * $metadata['fps']))
    ));
    $rows = intval(ceil($frameCount / $metadata['columns']));
    $directory = pichome_frame_atlas_cache_dir($rid, $profile);
    $atlasFile = $directory.'atlas-'.$metadata['revision'].'-'.$second.'.webp';
    if (!is_file($atlasFile) || filesize($atlasFile) < 32) {
        $lockFile = $atlasFile.'.lock';
        $lock = @fopen($lockFile, 'c');
        if (!$lock || !@flock($lock, LOCK_EX | LOCK_NB)) {
            if ($lock) @fclose($lock);
            pichome_frame_atlas_error('atlas_busy', '帧图集暂时不可用', 503);
        }
        clearstatcache(true, $atlasFile);
        if (!is_file($atlasFile) || filesize($atlasFile) < 32) {
            $ffmpeg = pichome_frame_atlas_binary('ffmpeg');
            if (!is_file($ffmpeg) || !is_executable($ffmpeg)) {
                @flock($lock, LOCK_UN);
                @fclose($lock);
                pichome_frame_atlas_error('ffmpeg_unavailable', '服务器未安装可用的 FFmpeg', 503);
            }
            $temporary = $atlasFile.'.'.strtolower(random(8)).'.tmp.webp';
            $generationLock = false;
            for ($slot = 0; $slot < 2; $slot++) {
                $slotFile = rtrim(getglobal('setting/attachdir'), '/\\') . BS . 'cache' . BS . 'frame-atlas-worker-' . $slot . '.lock';
                $candidateLock = @fopen($slotFile, 'c');
                if ($candidateLock && @flock($candidateLock, LOCK_EX | LOCK_NB)) {
                    $generationLock = $candidateLock;
                    break;
                }
                if ($candidateLock) @fclose($candidateLock);
            }
            if (!$generationLock) {
                @flock($lock, LOCK_UN);
                @fclose($lock);
                pichome_frame_atlas_error('atlas_busy', '服务器正在生成其他帧图集，请稍后重试', 503);
            }
            $filter = 'fps='.number_format($metadata['fps'], 6, '.', '')
                .',scale='.$metadata['cellWidth'].':'.$metadata['cellHeight'].':flags=lanczos'
                .',tile='.$metadata['columns'].'x'.$rows.':nb_frames='.$frameCount.':margin=0:padding=0';
            $command = (!strstr(PHP_OS, 'WIN') && is_executable('/usr/bin/timeout')
                    ? escapeshellarg('/usr/bin/timeout').' -k 3s 45s '
                    : '')
                .escapeshellarg($ffmpeg)
                .' -hide_banner -loglevel error -y -filter_threads 1 -threads 2'
                .' -ss '.escapeshellarg(number_format($segmentStart, 6, '.', ''))
                .' -i '.escapeshellarg($source['file'])
                .' -t '.escapeshellarg(number_format($segmentDuration, 6, '.', ''))
                .' -an -sn -vf '.escapeshellarg($filter)
                .' -frames:v 1 -c:v libwebp -threads 2 -quality '
                .$profileConfig['quality'].' '.escapeshellarg($temporary);
            $output = array();
            $status = 1;
            @exec($command.' 2>&1', $output, $status);
            @flock($generationLock, LOCK_UN);
            @fclose($generationLock);
            if ($status !== 0 || !is_file($temporary) || filesize($temporary) < 32) {
                @unlink($temporary);
                @flock($lock, LOCK_UN);
                @fclose($lock);
                runlog('frame_atlas', 'FFmpeg failed for rid '.$rid.' segment '.$second.': '.implode(' ', array_slice($output, -3)));
                pichome_frame_atlas_error('atlas_failed', '帧图集生成失败', 500);
            }
            @chmod($temporary, 0664);
            if (!@rename($temporary, $atlasFile)) {
                @unlink($temporary);
                @flock($lock, LOCK_UN);
                @fclose($lock);
                pichome_frame_atlas_error('atlas_write_failed', '帧图集保存失败', 500);
            }
        }
        @flock($lock, LOCK_UN);
        @fclose($lock);
    }

    $etag = '"frame-atlas-'.$metadata['revision'].'-'.$second.'"';
    if (!headers_sent()) {
        header('Content-Type: image/webp');
        header('Content-Length: '.filesize($atlasFile));
        header('Cache-Control: private, max-age=31536000, immutable');
        header('Vary: Cookie', false);
        header('ETag: '.$etag);
        header('X-Pichome-Atlas-Frames: '.$frameCount);
        header('X-Pichome-Atlas-Rows: '.$rows);
        header('Access-Control-Allow-Origin: *');
    }
    if (isset($_SERVER['HTTP_IF_NONE_MATCH']) && trim($_SERVER['HTTP_IF_NONE_MATCH']) === $etag) {
        http_response_code(304);
        exit;
    }
    if (!empty($_SERVER['REQUEST_METHOD']) && strtoupper($_SERVER['REQUEST_METHOD']) === 'HEAD') {
        exit;
    }
    readfile($atlasFile);
    exit;
}

function pichome_frame_atlas_handle($operation, $rid, $resourcesdata, $appdata) {
    if (!$resourcesdata
        || !empty($resourcesdata['isdelete'])
        || getTypeByExt($resourcesdata['ext']) != 'video') {
        pichome_frame_atlas_error('invalid_resource', '当前素材不是可预览的视频', 404);
    }
    $source = pichome_frame_atlas_source($rid, $resourcesdata, $appdata);
    if (!$source) {
        pichome_frame_atlas_error('source_unavailable', '视频源文件不可读取', 404);
    }
    $requestedProfile = isset($_GET['profile']) ? (string)$_GET['profile'] : 'default';
    $profileConfig = pichome_frame_atlas_profile_config($requestedProfile);
    $profile = $profileConfig['name'];
    $metadata = pichome_frame_atlas_probe($rid, $resourcesdata, $source, $profile);
    if ($operation === 'frameAtlasManifest') {
        pichome_frame_atlas_serve_manifest($metadata);
    }
    pichome_frame_atlas_serve_image($rid, $source, $metadata, $profile);
}

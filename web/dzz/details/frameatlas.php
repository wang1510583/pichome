<?php
if (!defined('IN_OAOOA')) {
    exit('Access Denied');
}

/**
 * Read-only, revisioned video frame atlases for the Android detail viewer.
 *
 * Each atlas covers one second. The manifest supplies the fixed sampling rate and
 * cell geometry, while the image endpoint lazily creates an immutable WebP tile.
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

function pichome_frame_atlas_cache_dir($rid) {
    $directory = rtrim(getglobal('setting/attachdir'), '/\\')
        .BS.'cache'.BS.'frame-atlas-v2'.BS.hash('sha256', (string)$rid).BS;
    dmkdir($directory);
    return $directory;
}

function pichome_frame_atlas_probe($rid, $resourcesdata, $source) {
    $file = $source['file'];
    $size = @filesize($file);
    $mtime = @filemtime($file);
    $revision = substr(hash('sha256', implode('|', array(
        'frame-atlas-v2',
        (string)$rid,
        (string)$source['relativePath'],
        (string)$size,
        (string)$mtime,
        isset($resourcesdata['md5']) ? (string)$resourcesdata['md5'] : '',
        isset($resourcesdata['mtime']) ? (string)$resourcesdata['mtime'] : ''
    ))), 0, 24);
    $directory = pichome_frame_atlas_cache_dir($rid);
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

    // A 60 fps ceiling keeps one-second atlases below common 4096px GPU texture limits.
    $fps = min(60, max(1, $fps));
    $nearestIntegerRate = round($fps);
    if (abs($fps - $nearestIntegerRate) < 0.01) {
        $fps = $nearestIntegerRate;
    }
    $fps = round($fps, 6);
    $longEdge = 320;
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
    $framesPerSegment = max(1, intval(ceil($fps)));
    $columns = 8;
    $rows = intval(ceil($framesPerSegment / $columns));
    $metadata = array(
        'success' => true,
        'version' => 2,
        'rid' => (string)$rid,
        'revision' => $revision,
        'duration' => round($duration, 6),
        'fps' => $fps,
        'sourceWidth' => $width,
        'sourceHeight' => $height,
        'cellWidth' => $cellWidth,
        'cellHeight' => $cellHeight,
        'columns' => $columns,
        'rows' => $rows,
        'framesPerSegment' => $framesPerSegment,
        'segmentSeconds' => 1,
        'maxSegment' => max(0, intval(ceil($duration)) - 1)
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

function pichome_frame_atlas_serve_image($rid, $source, $metadata) {
    $revision = isset($_GET['revision']) ? trim($_GET['revision']) : '';
    if (!$revision || !hash_equals($metadata['revision'], $revision)) {
        pichome_frame_atlas_error('revision_changed', '视频版本已变化，请重新获取帧图集信息', 409);
    }
    $second = isset($_GET['second']) ? intval($_GET['second']) : -1;
    if ($second < 0 || $second > $metadata['maxSegment']) {
        pichome_frame_atlas_error('invalid_segment', '帧图集分段超出视频范围', 416);
    }
    $segmentDuration = min(1, max(0, $metadata['duration'] - $second));
    $frameCount = max(1, min(
        $metadata['framesPerSegment'],
        intval(ceil($segmentDuration * $metadata['fps']))
    ));
    $rows = intval(ceil($frameCount / $metadata['columns']));
    $directory = pichome_frame_atlas_cache_dir($rid);
    $atlasFile = $directory.'atlas-'.$metadata['revision'].'-'.$second.'.webp';
    if (!is_file($atlasFile) || filesize($atlasFile) < 32) {
        $lockFile = $atlasFile.'.lock';
        $lock = @fopen($lockFile, 'c');
        if (!$lock || !@flock($lock, LOCK_EX)) {
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
            $filter = 'fps='.number_format($metadata['fps'], 6, '.', '')
                .',scale='.$metadata['cellWidth'].':'.$metadata['cellHeight'].':flags=lanczos'
                .',tile='.$metadata['columns'].'x'.$rows.':nb_frames='.$frameCount.':margin=0:padding=0';
            $command = (!strstr(PHP_OS, 'WIN') && is_executable('/usr/bin/timeout')
                    ? escapeshellarg('/usr/bin/timeout').' -k 3s 45s '
                    : '')
                .escapeshellarg($ffmpeg)
                .' -hide_banner -loglevel error -y'
                .' -ss '.escapeshellarg(number_format($second, 6, '.', ''))
                .' -i '.escapeshellarg($source['file'])
                .' -t '.escapeshellarg(number_format($segmentDuration, 6, '.', ''))
                .' -an -sn -vf '.escapeshellarg($filter)
                .' -frames:v 1 -c:v libwebp -quality 80 '.escapeshellarg($temporary);
            $output = array();
            $status = 1;
            @exec($command.' 2>&1', $output, $status);
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
        @unlink($lockFile);
    }

    $etag = '"frame-atlas-'.$metadata['revision'].'-'.$second.'"';
    if (!headers_sent()) {
        header('Content-Type: image/webp');
        header('Content-Length: '.filesize($atlasFile));
        header('Cache-Control: private, max-age=31536000, immutable');
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
    $metadata = pichome_frame_atlas_probe($rid, $resourcesdata, $source);
    if ($operation === 'frameAtlasManifest') {
        pichome_frame_atlas_serve_manifest($metadata);
    }
    pichome_frame_atlas_serve_image($rid, $source, $metadata);
}

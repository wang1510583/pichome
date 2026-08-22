<?php
/*
 * @copyright   QiaoQiaoShiDai Internet Technology(Shanghai)Co.,Ltd
 * @license     https://www.oaooa.com/licenses/
 *
 * @link        https://www.oaooa.com
 * @author      zyx(zyx@oaooa.com)
 */
if (!defined('IN_OAOOA')) {
    exit('Access Denied');
}
global $_G;
require_once __DIR__ . '/mediaCache.php';
$stableRid = isset($_GET['rid']) ? pichome_media_normalize_rid($_GET['rid']) : '';
$resourcesdata = false;
if ($stableRid !== '') {
    $resourcesdata = pichome_media_fetch_authorized_resource($stableRid);
    if (!$resourcesdata) {
        @header('HTTP/1.1 403 Forbidden');
        @header('Status: 403 Forbidden');
        exit('No Permission');
    }
    $path = $stableRid;
} else {
    $path = isset($_GET['path']) ? trim($_GET['path']):'';
    if(!$path = Decode($path,'read')){
        @header( 'HTTP/1.1 403 Not Found' );
        @header( 'Status: 403 Not Found' );
        exit('File not found');
    }
    //兼容老版本
    $path=str_replace('_3','',$path);
}

$rid = $path;

//是否忽略密级权限
$ulevel = intval(getglobal('pichomelevel') );
//是否获取真实文件地址

if($resourcesdata){
    // Stable RID request has already fetched and authorized this resource.
} elseif(strpos($path, 'attach::') === 0){
    $thumbpath = $path;
    $resourcesdata = IO::getMeta($path);
}else {
    $resourcesdata = C::t('pichome_resources')->fetch($rid);
}
if(!$resourcesdata){
    exit('file is not exists');
}

$resourcesdata['name'] = preg_replace('/\.'.$resourcesdata['ext'] . '/', '', $resourcesdata['name']);
$resourcesdata['name'] .='.'. $resourcesdata['ext'];

if($resourcesdata['level']  &&  $ulevel < $resourcesdata['level']){
    @header('HTTP/1.1 403 No Perm');
    @header('Status: 404 No Perm');
    exit('No Level Permission');
}

$url = IO::getStream($path);

$filename = $_GET['filename'] ? getstr($_GET['filename']) : $resourcesdata['name'];

// 定义要移除的后缀
$suffix = '\.dzz$';

// 使用正则表达式替换后缀
$filename = preg_replace('/' . $suffix . '/', '', $filename);

$ext = strtolower(substr(strrchr($filename, '.'), 1, 10));
if (!$ext) $ext = strtolower(substr(strrchr(preg_replace("/\.dzz$/i", '', preg_replace("/\?.*/i", '', $url)), '.'), 1, 10));

$mime = dzz_mime::get_type($ext);

if (is_file($url)) {
    $name = $filename;
    $total = filesize($url);
    $lastModified = @filemtime($url);
    if (!$lastModified) {
        $lastModified = time();
    }
    $etag = pichome_media_cache_etag($url, $total, $lastModified);
    pichome_media_send_cache_headers($etag, $lastModified);

    $hasRange = isset($_SERVER['HTTP_RANGE'])
        && pichome_media_cache_if_range_matches($etag, $lastModified);
    if (!$hasRange && pichome_media_cache_is_not_modified($etag, $lastModified)) {
        header('HTTP/1.1 304 Not Modified');
        exit();
    }

    if ($hasRange) {
        $rangeHeader = trim($_SERVER['HTTP_RANGE']);
        $range = pichome_media_parse_range($rangeHeader, $total);
        if ($range === false) {
            header('HTTP/1.1 416 Requested Range Not Satisfiable');
            header('Content-Range: bytes */' . $total);
            exit();
        }

        list($start, $end) = $range;

        $size = $end - $start + 1;
        header('HTTP/1.1 206 Partial Content');
        header('Accept-Ranges: bytes');
        header('Content-Length:' . $size);
        header('Content-Range: bytes ' . $start . '-' . $end . '/' . $total);
        header('Content-Type:' . $mime);

        if (isset($_SERVER['REQUEST_METHOD']) && strtoupper($_SERVER['REQUEST_METHOD']) === 'HEAD') {
            exit();
        }

        $fp = fopen($url, 'rb');
        if ($fp === false) {
            exit('Cannot open file');
        }
        fseek($fp, $start, 0);
        $cur = $start;
        @ob_end_clean();
        while (!feof($fp) && $cur <= $end && (connection_status() == 0)) {
            print fread($fp, min(1024 * 16, ($end - $cur) + 1));
            $cur += 1024 * 16;
            @ob_flush();
            @flush();
        }
        fclose($fp);
        exit();
    }

    // ============ 非 Range 请求(完整下载) ============
    header('HTTP/1.1 200 OK');
    header('Accept-Ranges: bytes');
    header('Content-Length:' . $total);
    header('Content-Type:' . $mime);

    if (isset($_SERVER['REQUEST_METHOD']) && strtoupper($_SERVER['REQUEST_METHOD']) === 'HEAD') {
        exit();
    }

    $fp = fopen($url, 'rb');
    if ($fp !== false) {
        $cur = 0;
        @ob_end_clean();
        while (!feof($fp) && $cur < $total && (connection_status() == 0)) {
            print fread($fp, min(1024 * 16, $total - $cur));
            $cur += 1024 * 16;
            @ob_flush();
            @flush();
        }
        fclose($fp);
    }
    exit();
} else {
    header('HTTP/1.1 302 Found');
    header('Accept-Ranges: bytes');
    header('Content-Type: ' . $mime);
    header('Location: ' . $url);
    exit();
}

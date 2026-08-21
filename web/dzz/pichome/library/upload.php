<?php
if (!defined('IN_OAOOA')) {
    exit('Access Denied');
}
global $_G;
if (isset($_G['setting'])) $setting = $_G['setting'];
else  $setting = C::t('setting')->fetch_all();
$themeid = isset($_G['setting']['pichometheme']) ? intval($_G['setting']['pichometheme']):1;
$themedata = getthemedata($themeid);
updatesession();
$operation = isset($_GET['operation']) ? trim($_GET['operation']) : '';

function pichome_remote_json($success, $data = array(), $message = '') {
    header('Content-Type: application/json; charset=utf-8');
    exit(json_encode(array('success' => $success, 'data' => $data, 'msg' => $message), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
}

function pichome_remote_request_data() {
    $data = array_merge($_GET, $_POST);
    $decoded = json_decode(file_get_contents('php://input'), true);
    if (is_array($decoded)) $data = array_merge($data, $decoded);
    return $data;
}

function pichome_remote_public_ipv4($host) {
    if (filter_var($host, FILTER_VALIDATE_IP, FILTER_FLAG_IPV4)) {
        return filter_var($host, FILTER_VALIDATE_IP, FILTER_FLAG_IPV4 | FILTER_FLAG_NO_PRIV_RANGE | FILTER_FLAG_NO_RES_RANGE) ? $host : '';
    }
    $records = @dns_get_record($host, DNS_A);
    if (!is_array($records)) return '';
    foreach ($records as $record) {
        $ip = isset($record['ip']) ? $record['ip'] : '';
        if ($ip && filter_var($ip, FILTER_VALIDATE_IP, FILTER_FLAG_IPV4 | FILTER_FLAG_NO_PRIV_RANGE | FILTER_FLAG_NO_RES_RANGE)) return $ip;
    }
    return '';
}

function pichome_remote_absolute_url($base, $location) {
    $location = trim($location);
    if (preg_match('#^https?://#i', $location)) return $location;
    $parts = parse_url($base);
    if (!$parts || empty($parts['scheme']) || empty($parts['host'])) return '';
    if (strpos($location, '//') === 0) return $parts['scheme'] . ':' . $location;
    $origin = $parts['scheme'] . '://' . $parts['host'] . (isset($parts['port']) ? ':' . intval($parts['port']) : '');
    if (strpos($location, '/') === 0) return $origin . $location;
    $directory = isset($parts['path']) ? preg_replace('#/[^/]*$#', '/', $parts['path']) : '/';
    return $origin . $directory . $location;
}

function pichome_remote_download_media($sourceUrl) {
    global $_G;
    if (!function_exists('curl_init')) return array('error' => '服务器未启用远程下载组件');
    $maxBytes = 2 * 1024 * 1024 * 1024;
    $temp = tempnam($_G['setting']['attachdir'] . 'cache/', 'pichome_remote_');
    if (!$temp) return array('error' => '无法创建远程素材临时文件');
    $lastHeaders = array();
    $finalUrl = trim($sourceUrl);

    for ($redirect = 0; $redirect <= 5; $redirect++) {
        $parts = parse_url($finalUrl);
        $scheme = isset($parts['scheme']) ? strtolower($parts['scheme']) : '';
        $host = isset($parts['host']) ? strtolower($parts['host']) : '';
        $port = isset($parts['port']) ? intval($parts['port']) : ($scheme === 'https' ? 443 : 80);
        if (!in_array($scheme, array('http', 'https'), true) || !$host || isset($parts['user']) || isset($parts['pass'])) {
            @unlink($temp);
            return array('error' => '只支持公开的 HTTP/HTTPS 素材地址');
        }
        if (($scheme === 'http' && $port !== 80) || ($scheme === 'https' && $port !== 443)) {
            @unlink($temp);
            return array('error' => '远程素材地址端口不受支持');
        }
        $ip = pichome_remote_public_ipv4($host);
        if (!$ip) {
            @unlink($temp);
            return array('error' => '远程素材地址不是可访问的公网地址');
        }

        $output = @fopen($temp, 'wb');
        if (!$output) {
            @unlink($temp);
            return array('error' => '无法写入远程素材临时文件');
        }
        $headers = array();
        $received = 0;
        $ch = curl_init($finalUrl);
        curl_setopt_array($ch, array(
            CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_CONNECTTIMEOUT => 12,
            CURLOPT_TIMEOUT => 1800,
            CURLOPT_USERAGENT => 'Mozilla/5.0 (compatible; Pichome Remote Media Import/1.0)',
            CURLOPT_HTTPHEADER => array('Accept: video/mp4,video/webm,video/quicktime,video/*;q=0.9,image/webp,image/svg+xml,image/*;q=0.8,*/*;q=0.1'),
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
            CURLOPT_RESOLVE => array($host . ':' . $port . ':' . $ip),
            CURLOPT_HEADERFUNCTION => function ($curl, $line) use (&$headers) {
                $length = strlen($line);
                $position = strpos($line, ':');
                if ($position !== false) $headers[strtolower(trim(substr($line, 0, $position)))] = trim(substr($line, $position + 1));
                return $length;
            },
            CURLOPT_WRITEFUNCTION => function ($curl, $chunk) use ($output, &$received, $maxBytes) {
                $length = strlen($chunk);
                $received += $length;
                if ($received > $maxBytes) return 0;
                return fwrite($output, $chunk);
            }
        ));
        $ok = curl_exec($ch);
        $status = intval(curl_getinfo($ch, CURLINFO_RESPONSE_CODE));
        $curlError = curl_error($ch);
        curl_close($ch);
        fclose($output);

        if ($received > $maxBytes) {
            @unlink($temp);
            return array('error' => '在线素材超过 2 GB，已停止下载');
        }
        if ($status >= 300 && $status < 400 && !empty($headers['location'])) {
            $next = pichome_remote_absolute_url($finalUrl, $headers['location']);
            if (!$next) {
                @unlink($temp);
                return array('error' => '远程素材重定向地址无效');
            }
            $finalUrl = $next;
            continue;
        }
        if (!$ok || $status < 200 || $status >= 300 || $received < 1) {
            @unlink($temp);
            return array('error' => $curlError ? '下载在线素材失败：' . $curlError : '下载在线素材失败（HTTP ' . $status . '）');
        }
        $lastHeaders = $headers;
        break;
    }

    if (!is_file($temp) || filesize($temp) < 1) {
        @unlink($temp);
        return array('error' => '在线素材下载结果为空');
    }
    $finfo = new finfo(FILEINFO_MIME_TYPE);
    $mime = strtolower(strval($finfo->file($temp)));
    $extensions = array(
        'image/jpeg' => 'jpg', 'image/png' => 'png', 'image/gif' => 'gif',
        'image/webp' => 'webp', 'image/bmp' => 'bmp', 'image/x-ms-bmp' => 'bmp',
        'image/svg+xml' => 'svg',
        'video/mp4' => 'mp4', 'video/quicktime' => 'mov',
        'video/x-matroska' => 'mkv', 'application/x-matroska' => 'mkv',
        'video/webm' => 'webm', 'video/x-msvideo' => 'avi', 'video/avi' => 'avi',
        'video/x-m4v' => 'm4v'
    );
    if (!isset($extensions[$mime])) {
        $sample = file_get_contents($temp, false, null, 0, 4096);
        if ($sample !== false && preg_match('/<svg\b/i', $sample)) {
            $mime = 'image/svg+xml';
        } else {
            @unlink($temp);
            return array('error' => '拖入的在线地址不是受支持的图片或视频文件');
        }
    }
    $extension = $extensions[$mime];
    $name = '';
    if (!empty($lastHeaders['content-disposition']) && preg_match('/filename\*?=(?:UTF-8\'\')?["\']?([^"\';]+)/i', $lastHeaders['content-disposition'], $match)) {
        $name = rawurldecode(trim($match[1]));
    }
    if (!$name) {
        $path = parse_url($finalUrl, PHP_URL_PATH);
        $name = rawurldecode(basename($path ?: ''));
    }
    $basename = pathinfo($name, PATHINFO_FILENAME);
    $basename = preg_replace('/[\\\\\/:*?"<>|\x00-\x1F]+/u', '_', trim($basename));
    if ($basename === '') $basename = 'online-media-' . date('Ymd-His');
    if (function_exists('mb_strcut')) $basename = mb_strcut($basename, 0, 160, 'UTF-8');
    else $basename = substr($basename, 0, 160);
    return array('path' => $temp, 'filename' => $basename . '.' . $extension, 'url' => $finalUrl, 'size' => filesize($temp));
}

if ($operation === 'remoteupload') {
	@set_time_limit(1800);
    $request = pichome_remote_request_data();
    $appid = isset($request['appid']) ? trim($request['appid']) : '';
    $pfid = isset($request['pfid']) ? trim($request['pfid']) : '';
    $sourceUrl = isset($request['url']) ? trim($request['url']) : '';
    $appdata = $appid ? C::t('pichome_vapp')->fetch($appid) : array();
    if (!$appdata) pichome_remote_json(false, array(), '素材库不存在');
    if (!in_array(intval($appdata['type']), array(0, 3), true)) pichome_remote_json(false, array(), '当前素材库不支持在线素材上传');
    if (intval($_G['adminid']) !== 1 && intval($appdata['uid']) !== intval($_G['uid'])) pichome_remote_json(false, array(), '没有素材库写入权限');
    if (!$sourceUrl || strlen($sourceUrl) > 4096) pichome_remote_json(false, array(), '在线素材地址无效');
    if ($pfid && !DB::result_first('select fid from %t where fid=%s and appid=%s', array('pichome_folder', $pfid, $appid))) {
        pichome_remote_json(false, array(), '目标分类不存在');
    }
    $download = pichome_remote_download_media($sourceUrl);
    if (!empty($download['error'])) pichome_remote_json(false, array(), $download['error']);
    try {
        $result = IO::uploadStream($download['path'], $download['filename'], $appid, $pfid, '', array());
    } catch (Throwable $error) {
        @unlink($download['path']);
        pichome_remote_json(false, array(), '在线素材入库失败：' . $error->getMessage());
    }
    @unlink($download['path']);
    if (!$result || !empty($result['error'])) pichome_remote_json(false, array(), !empty($result['error']) ? $result['error'] : '在线素材入库失败');
    pichome_remote_json(true, $result, '在线素材已添加');
}
elseif($operation == 'upload'){//上传文件图标类
    include libfile( 'class/uploadhandler' );

    $options = array( 'accept_file_types' => '/\.(gif|jpe?g|png|svg|webp)$/i',

        'upload_dir' => $_G[ 'setting' ][ 'attachdir' ] . 'cache/',

        'upload_url' => $_G[ 'setting' ][ 'attachurl' ] . 'cache/',

        'thumbnail' => array( 'max-width' => 40, 'max-height' => 40 ) );

    $upload_handler = new uploadhandler( $options );
    updatesession();
    exit();
}
elseif ( $operation == 'uploads' ) { //上传新文件(指新建)

    $appid = isset($_GET[ 'container' ]) ? trim( $_GET[ 'container' ] ):'';

    //$folderdata = C::t( 'folder' )->fetch_by_fid( $container );
    $appdata = C::t('pichome_vapp')->fetch($appid);
	if (!$appdata) {
		exit(json_encode(array('files' => array(array('error' => '素材库不存在')))));
	}
	if (intval($appdata['type']) === 0 && intval($_G['adminid']) !== 1 && intval($appdata['uid']) !== intval($_G['uid'])) {
		exit(json_encode(array('files' => array(array('error' => '没有 Eagle 素材库写入权限')))));
	}
	if (intval($appdata['type']) === 0 && (!is_dir($appdata['path']) || !is_writable($appdata['path']))) {
		exit(json_encode(array('files' => array(array('error' => 'Eagle 素材库目录不可写')))));
	}
	// Browser image drags can contain valid bytes but an extensionless name such
	// as "640".  The chunk uploader normally relies on Content-Disposition for
	// the name, so normalize both that header and PHP's multipart metadata.
	$disposition = isset($_SERVER['HTTP_CONTENT_DISPOSITION']) ? $_SERVER['HTTP_CONTENT_DISPOSITION'] : '';
	$uploadName = '';
	if ($disposition && preg_match('/filename="([^"]*)"/i', $disposition, $nameMatch)) {
		$uploadName = rawurldecode($nameMatch[1]);
	}
	if (!$uploadName && isset($_FILES['files']['name'][0])) $uploadName = $_FILES['files']['name'][0];
	if ($uploadName && pathinfo($uploadName, PATHINFO_EXTENSION) === '') {
		$tmpName = isset($_FILES['files']['tmp_name'][0]) ? $_FILES['files']['tmp_name'][0] : '';
		if ($tmpName && is_uploaded_file($tmpName)) {
			$finfo = new finfo(FILEINFO_MIME_TYPE);
			$mime = strtolower(strval($finfo->file($tmpName)));
			$mimeExtensions = array(
				'image/jpeg' => 'jpg', 'image/png' => 'png', 'image/gif' => 'gif',
				'image/webp' => 'webp', 'image/bmp' => 'bmp', 'image/x-ms-bmp' => 'bmp',
				'image/svg+xml' => 'svg',
				'video/mp4' => 'mp4', 'video/quicktime' => 'mov',
				'video/x-matroska' => 'mkv', 'application/x-matroska' => 'mkv',
				'video/webm' => 'webm', 'video/x-msvideo' => 'avi', 'video/avi' => 'avi',
				'video/x-m4v' => 'm4v'
			);
			if (isset($mimeExtensions[$mime])) {
				$uploadName .= '.' . $mimeExtensions[$mime];
				$_SERVER['HTTP_CONTENT_DISPOSITION'] = 'attachment; filename="' . rawurlencode($uploadName) . '"';
				$_FILES['files']['name'][0] = $uploadName;
			}
		}
	}
    //$space = dzzgetspace( $uid );

    //$space[ 'self' ] = intval( $space[ 'self' ] );

    $bz = trim( $_GET[ 'bz' ] );

    require_once dzz_libfile( 'class/UploadHandler' );

    $extlimitstr =  $appdata[ 'allowext' ] ? str_replace('*.','',$appdata['allowext']):'';
    //上传类型
    $allowedExtensions = $extlimitstr ? explode(',',$extlimitstr) : array();
    $notallowdExtensions = $appdata[ 'notallowext' ] ? explode(',',str_replace('*.','',$appdata['notallowext'])):array();
	if (intval($appdata['type']) === 0) {
		$allowedExtensions = array('jpg','jpeg','png','gif','webp','bmp','svg','mp4','mov','mkv','webm','avi','m4v');
		$notallowdExtensions = array();
	}
    // $sizeLimit = ( $space[ 'maxattachsize' ] );

    $options = array( 'accept_file_types' => $allowedExtensions ? ( "/(\.|\/)(" . $allowedExtensions . ")$/i" ) : "/.+$/i",
        'accept_file_names' => "/.+$/i",
        'notallow_file_type'=>$notallowdExtensions ? ( "/(\.|\/)(" . $notallowdExtensions . ")$/i" ) : "",
        'max_file_size' => null,//$sizeLimit ? $sizeLimit : null,
        'upload_dir' => $_G[ 'setting' ][ 'attachdir' ] . 'cache/',
        'upload_url' => $_G[ 'setting' ][ 'attachurl' ] . 'cache/',

    );

    $upload_handler = new UploadHandler( $options );
    updatesession();
    exit();

} elseif ( $operation == 'chkmd5' ) {//检查是否已有文件
    $md5 = isset($_GET['md5']) ? trim($_GET['md5']) : '';
    $path = isset($_GET['relativePath']) ? trim($_GET['relativePath']) : '';
    $pfid = isset($_GET['pfid']) ? trim($_GET['pfid']) : '';
    $appid = isset($_GET['appid']) ? trim($_GET['appid']) : '';
    if (!$appid) {
        $data['error'] = 'uploadfailer';
        $data['msg'] = '缺少appid';
        exit(json_encode(array('success' => true, 'data' => $data)));
    }
    $chkappdata = C::t('pichome_vapp')->fetch($appid);
    // Eagle files must always pass through the Eagle writer. Reusing a normal
    // FilePress attachment here would create only a database copy and would not
    // put the file into the .library package.
    if ($chkappdata && intval($chkappdata['type']) === 0) {
        exit(json_encode(array('success' => false)));
    }
    if ($md5) {
        $attachment = C::t('attachment')->fetch_by_md5($md5);
    }

    //如果文件存在，则查询当前库是否有该文件
    if ($attachment) {
        $aid = $attachment['aid'];
        //处理目录数据
        $patharr = explode('/', $path);
        array_pop($patharr);
        $dirname = ($patharr) ? implode('/', $patharr) : '';
        $relativepath = $dirname;

        $folderdata = C::t('pichome_folder')->createfolerbypath($appid, $relativepath, $pfid);
        //如果当前库有该文件
        if ($rid = DB::result_first("select rid from %t where path = %s and appid = %s ", array('pichome_resources_attr', $aid, $appid))) {
            $resourcesdata = C::t('pichome_resources')->fetch($rid);
            if($resourcesdata['isdelete']){
                $rsetarr = [
                    'lastdate' => TIMESTAMP * 1000,
                    'appid' => $appid,
                    'uid'=>$_G['uid'],
                    'username'=>$_G['username'],
                    'apptype' => 3,
                    'size' => $resourcesdata['size'],
                    'type' => $resourcesdata['type'],
                    'ext' => $resourcesdata['ext'],
                    'mtime' => TIMESTAMP * 1000,
                    'dateline' => TIMESTAMP * 1000,
                    'btime' => TIMESTAMP * 1000,
                    'width' => $resourcesdata['width'],
                    'height' => $resourcesdata['height'],
                    'lastdate' => TIMESTAMP,
                    'level' => isset($folderdata['level']) ? $folderdata['level'] : 0,
                    'name' => $resourcesdata['name'],
                    'fids' => $folderdata['fid'] ? $folderdata['fid']:''
                ];

                if ($rsetarr['rid'] = C::t('pichome_resources')->insert_data($rsetarr)) {//插入主表
                    Hook::listen('lang_parse',$rsetarr,['saveResourcesLangData',$rsetarr['rid']]);
                    //获取附属表数据
                    $attrdata = C::t('pichome_resources_attr')->fetch($rid);
                    $attrdata['rid'] = $rsetarr['rid'];
                    $attrdata['appid'] = $appid;
                    $attrdata['searchval'] = $rsetarr['name'];
                    C::t('attachment')->addcopy_by_aid($attrdata['path']);//增加图片使用数
                    C::t('pichome_resources_attr')->insert($attrdata);
                    //目录数据
                    if ($folderdata['fid']) {
                        $frsetarr = ['appid' => $appid, 'rid' => $rsetarr['rid'], 'fid' => $folderdata['fid']];
                        C::t('pichome_folderresources')->insert($frsetarr);
                        C::t('pichome_folder')->add_filenum_by_fid($folderdata['fid'], 1);
                    }
                    //缩略图数据
                    $thumbrecorddata = C::t('thumb_record')->fetch($rid);
                    $thumbrecorddata['rid'] = $rsetarr['rid'];

                    C::t('thumb_record')->insert_data($thumbrecorddata);

                    //颜色数据
                    foreach (DB::fetch_all("select * from %t where rid = %s", array('pichome_palette', $rid)) as $v) {
                        $v['rid'] = $rsetarr['rid'];
                        unset($v['id']);
                        C::t('pichome_palette')->insert($v);
                    }
                    C::t('pichome_vapp')->addcopy_by_appid($appid);
                    $data = C::t('pichome_resources')->fetch_by_rid($rsetarr['rid']);
                    $data['folder'] = C::t('pichome_folder')->fetch_allfolder_by_fid($folderdata['fid']);
                    $data['addnum'] = 1;
                } else {
                    $data['error'] = 'uploadfailer';
                }
                exit(json_encode(array('success' => true, 'data' => $data)));
            }else{
                $nfids = explode(',', $resourcesdata['fids']);
                $iscurrentfolder = 1;
                if ($folderdata['fid'] && !in_array($folderdata['fid'], $nfids)) {
                    $nfids[] = $folderdata['fid'];
                    $iscurrentfolder = 0;
                }

                $icoarr = [
                    'lastdate' => TIMESTAMP * 1000,
                    'appid' => $appid,
                    'uid'=>$_G['uid'],
                    'username'=>$_G['username'],
                    'apptype' => 3,
                    'size' => $resourcesdata['size'],
                    'type' => $resourcesdata['type'],
                    'ext' => $resourcesdata['ext'],
                    'mtime' => TIMESTAMP * 1000,
                    'dateline' => TIMESTAMP * 1000,
                    'btime' => TIMESTAMP * 1000,
                    'width' => $resourcesdata['width'],
                    'height' => $resourcesdata['height'],
                    'lastdate' => TIMESTAMP,
                    'level' => isset($folderdata['level']) ? $folderdata['level'] : 0,
                    'name' => $resourcesdata['name'],
                    //'fids' => $nfids ? implode(',', $nfids) : '',
                    'rid' => $rid
                ];

                if (C::t('pichome_resources')->update($rid, $icoarr)) {//插入主表
                    //目录数据
                    if (!$iscurrentfolder && $folderdata['fid']) {
                        $frsetarr = ['appid' => $appid, 'rid' => $rid, 'fid' => $folderdata['fid']];
                        C::t('pichome_folderresources')->insert($frsetarr);
                        //C::t('pichome_folder')->add_filenum_by_fid($folderdata['fid'], 1);
                    }
                    //C::t('pichome_vapp')->addcopy_by_appid($appid);
                    $data = C::t('pichome_resources')->fetch_by_rid($rid);

                    $data['folder'] = C::t('pichome_folder')->fetch_allfolder_by_fid($folderdata['fid']);
                    $data['addnum'] = ($iscurrentfolder) ? 0:1;
                    $data['onlyfolderadd'] = 1;
                } else {
                    $data['error'] = 'uploadfailer';
                }
                $tmpdata = $data;
                Hook::listen('createafter_addindex', $tmpdata);
                unset($tmpdata);
                exit(json_encode(array('success' => true, 'data' => $data)));
            }

        }
        elseif ($rid = DB::result_first("select rid from %t where path = %s ", array('pichome_resources_attr', $aid))) {//如果当前库没有该文件，但其它库有
            //获取原文件基本数据
            $resourcesdata = C::t('pichome_resources')->fetch($rid);
            $rsetarr = [
                'lastdate' => TIMESTAMP * 1000,
                'appid' => $appid,
                'uid'=>$_G['uid'],
                'username'=>$_G['username'],
                'apptype' => 3,
                'size' => $resourcesdata['size'],
                'type' => $resourcesdata['type'],
                'ext' => $resourcesdata['ext'],
                'mtime' => TIMESTAMP * 1000,
                'dateline' => TIMESTAMP * 1000,
                'btime' => TIMESTAMP * 1000,
                'width' => $resourcesdata['width'],
                'height' => $resourcesdata['height'],
                'lastdate' => TIMESTAMP,
                'level' => isset($folderdata['level']) ? $folderdata['level'] : 0,
                'name' => $resourcesdata['name'],
                'fids' => $folderdata['fid'] ? $folderdata['fid']:''
            ];

            if ($rsetarr['rid'] = C::t('pichome_resources')->insert_data($rsetarr)) {//插入主表
                Hook::listen('lang_parse',$rsetarr,['saveResourcesLangData',$rsetarr['rid']]);
                //获取附属表数据
                $attrdata = C::t('pichome_resources_attr')->fetch($rid);
                $attrdata['rid'] = $rsetarr['rid'];
                $attrdata['appid'] = $appid;
                $attrdata['searchval'] = $rsetarr['name'];
                C::t('attachment')->addcopy_by_aid($attrdata['path']);//增加图片使用数
                C::t('pichome_resources_attr')->insert($attrdata);
                //目录数据
                if ($folderdata['fid']) {
                    $frsetarr = ['appid' => $appid, 'rid' => $rsetarr['rid'], 'fid' => $folderdata['fid']];
                    C::t('pichome_folderresources')->insert($frsetarr);
                    //C::t('pichome_folder')->add_filenum_by_fid($folderdata['fid'], 1);
                }
                //缩略图数据
                $thumbrecorddata = C::t('thumb_record')->fetch($rid);
                $thumbrecorddata['rid'] = $rsetarr['rid'];

                 C::t('thumb_record')->insert_data($thumbrecorddata);

                //颜色数据
                foreach (DB::fetch_all("select * from %t where rid = %s", array('pichome_palette', $rid)) as $v) {
                    $v['rid'] = $rsetarr['rid'];
                    unset($v['id']);
                    C::t('pichome_palette')->insert($v);
                }
                C::t('pichome_vapp')->addcopy_by_appid($appid);
                $data = C::t('pichome_resources')->fetch_by_rid($rsetarr['rid']);
                $data['folder'] = C::t('pichome_folder')->fetch_allfolder_by_fid($folderdata['fid']);
                $data['addnum'] = 1;
                addFileuploadStats($rsetarr['rid'],1);
            } else {
                $data['error'] = 'uploadfailer';
            }
            $tmpdata = $data;
            Hook::listen('createafter_addindex', $tmpdata);
            unset($tmpdata);
            exit(json_encode(array('success' => true, 'data' => $data)));

        }
    }else {
        exit(json_encode(array('success' => false)));
    }
}
elseif ( $operation == 'cloudupload' ) {
    global $_G;
    $data = $_GET;
    $path = $data[ 'bz' ] . ':' . $data[ 'did' ] . ':' .$data[ 'Key' ];
    $data['dirname'] = dirname($data['relativePath']);
    $return = IO::movetmpdataToattachment( $path, $data );
    if ( !isset( $return[ 'error' ] ) ) {
        exit( json_encode( array( 'success'=>true,'data' => $return ) ) );
    } else {
        exit( json_encode( array( 'success' =>false,'msg'=> $return[ 'error' ] ) ) );
    }
}

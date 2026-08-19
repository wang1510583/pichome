<?php
if (!defined('IN_OAOOA')) {
    exit('Access Denied');
}
$operation = isset($_GET['operation']) ? trim($_GET['operation']):'';

/**
 * Keep deletions from an Eagle library persistent. Pichome's type-0 library
 * is only an index; Eagle's metadata.json is the source of truth.
 */
function pichome_mark_eagle_deleted($rids)
{
    global $_G;
    $rows = DB::fetch_all(
        'select r.rid,r.appid,v.type,v.path,v.uid from %t r inner join %t v on v.appid=r.appid where r.rid in(%n)',
        array('pichome_resources', 'pichome_vapp', $rids)
    );

    foreach ($rows as $row) {
        if (intval($row['type']) !== 0) continue;
        if (intval($_G['adminid']) !== 1 && intval($row['uid']) !== intval($_G['uid'])) {
            return array('success' => false, 'msg' => '没有 Eagle 素材库删除权限');
        }

        $library = realpath($row['path']);
        if (!$library || !is_dir($library) || !is_writable($library)) {
            return array('success' => false, 'msg' => 'Eagle 素材库目录不可写');
        }
        $eid = C::t('#eagle#eagle_record')->fetch_eid_by_rid($row['rid'], $row['appid']);
        if (!$eid || !preg_match('/^[A-Z0-9]+$/i', $eid)) {
            return array('success' => false, 'msg' => '没有找到对应的 Eagle 素材记录');
        }

        $imagesroot = realpath($library . DIRECTORY_SEPARATOR . 'images');
        $itemdir = realpath($library . DIRECTORY_SEPARATOR . 'images' . DIRECTORY_SEPARATOR . $eid . '.info');
        if (!$imagesroot || !$itemdir || strpos($itemdir . DIRECTORY_SEPARATOR, $imagesroot . DIRECTORY_SEPARATOR) !== 0) {
            return array('success' => false, 'msg' => 'Eagle 素材路径无效');
        }
        $metadatafile = $itemdir . DIRECTORY_SEPARATOR . 'metadata.json';
        if (!is_file($metadatafile) || !is_writable($metadatafile)) {
            return array('success' => false, 'msg' => 'Eagle metadata.json 不可写');
        }

        $lock = @fopen($metadatafile . '.lock', 'c+');
        if (!$lock || !flock($lock, LOCK_EX)) {
            if ($lock) fclose($lock);
            return array('success' => false, 'msg' => '无法锁定 Eagle 素材信息');
        }
        $metadata = json_decode(file_get_contents($metadatafile), true);
        if (!is_array($metadata)) {
            flock($lock, LOCK_UN);
            fclose($lock);
            return array('success' => false, 'msg' => 'Eagle metadata.json 格式错误');
        }
        $nowms = (int) round(microtime(true) * 1000);
        $metadata['isDeleted'] = true;
        $metadata['modificationTime'] = $nowms;
        $metadata['lastModified'] = $nowms;
        $tmpfile = $metadatafile . '.tmp.' . getmypid();
        $jsonflags = JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES;
        $saved = file_put_contents($tmpfile, json_encode($metadata, $jsonflags), LOCK_EX) !== false
            && @rename($tmpfile, $metadatafile);
        if (!$saved) @unlink($tmpfile);
        @chmod($metadatafile, 0664);
        flock($lock, LOCK_UN);
        fclose($lock);
        if (!$saved) return array('success' => false, 'msg' => '无法更新 Eagle 素材删除状态');

        // Notify Eagle and the next Pichome scan that this item changed.
        $mtimefile = $library . DIRECTORY_SEPARATOR . 'mtime.json';
        $mtimelock = @fopen($mtimefile . '.lock', 'c+');
        if ($mtimelock && flock($mtimelock, LOCK_EX)) {
            $mtimedata = is_file($mtimefile) ? json_decode(file_get_contents($mtimefile), true) : array();
            if (!is_array($mtimedata)) $mtimedata = array();
            $mtimedata[$eid] = $nowms;
            $mtimedata['all'] = isset($mtimedata['all']) ? intval($mtimedata['all']) + 1 : 1;
            $mtimetmp = $mtimefile . '.tmp.' . getmypid();
            if (file_put_contents($mtimetmp, json_encode($mtimedata, $jsonflags), LOCK_EX) !== false) {
                @rename($mtimetmp, $mtimefile);
                @chmod($mtimefile, 0664);
            } else {
                @unlink($mtimetmp);
            }
            flock($mtimelock, LOCK_UN);
            fclose($mtimelock);
        } elseif ($mtimelock) {
            fclose($mtimelock);
        }
    }
    return array('success' => true);
}

if($operation == 'deletefile'){//删除文件到回收站
    $rids = isset($_GET['rids']) ? trim($_GET['rids']):'';
    $rids = array_values(array_filter(explode(',',$rids)));
    if (!$rids) exit(json_encode(array('success'=>false,'msg'=>lang('params_error'))));
    $eagleresult = pichome_mark_eagle_deleted($rids);
    if (!$eagleresult['success']) exit(json_encode($eagleresult));
    C::t('pichome_resources')->recycle_data_by_rids($rids);
    exit(json_encode(array('success'=>true)));
}elseif($operation == 'removefolder'){//将文件从目录中移除
    $fid = isset($_GET['fid']) ? trim($_GET['fid']):'';
    $rids = isset($_GET['rids']) ? trim($_GET['rids']):'';
    $rids = explode(',',$rids);
    C::t('pichome_folderresources')->delete_by_ridfid($rids,$fid);
    exit(json_encode(array('success'=>true)));
}elseif($operation == 'finallydel'){//彻底删除文件
    if(getglobal('adminid') != 1){
        exit(json_encode(array('success'=>false,'msg'=>'没有权限')));
    }
    $rids = isset($_GET['rids']) ? trim($_GET['rids']):'';
    $rids = explode(',',$rids);
    C::t('pichome_resources')->delete_by_rid($rids);
    exit(json_encode(array('success'=>true)));
}elseif($operation == 'recoverfile'){//恢复文件
    $rids = isset($_GET['rids']) ? trim($_GET['rids']):'';
    $rids = explode(',',$rids);
    C::t('pichome_resources')->recover_file_by_rids($rids,['isdelete'=>0]);
    exit(json_encode(array('success'=>true)));
}elseif($operation == 'emptyrecycle'){//清空回收站
    $appid = isset($_GET['appid']) ? trim($_GET['appid']):'';
    if(!$appid)exit(json_encode(array('success'=>false,'msg'=>lang('params_error'))));
    if(getglobal('adminid') != 1){
        exit(json_encode(array('success'=>false,'msg'=>lang('no_perm'))));
    }
    C::t('pichome_resources')->empty_recycle_data($appid);
    dfsockopen(getglobal('localurl') . 'misc.php?mod=finalydelfile', 0, '', '', false, '',0.1);
    exit(json_encode(array('success'=>true)));
}

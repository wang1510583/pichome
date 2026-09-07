<?php
if (!defined('IN_OAOOA')) {
    exit('Access Denied');
}
$operation = isset($_GET['operation']) ? trim($_GET['operation']):'';

function pichome_set_deleted_state($rids, $deleted)
{
    $rids = array_values(array_unique(array_filter($rids)));
    if (!$rids) return array('success' => false, 'msg' => lang('params_error'));
    $rows = C::t('pichome_resources')->fetch_all($rids);
    if (count($rows) !== count($rids)) return array('success' => false, 'msg' => '素材不存在');
    foreach ($rows as $row) {
        if (intval($row['isdelete']) > 1) return array('success' => false, 'msg' => '素材已被彻底删除');
        if (!C::t('pichome_vappmember')->checkuserperm_by_appid($row['appid'])) {
            return array('success' => false, 'msg' => '没有素材库写入权限');
        }
    }
    foreach ($rows as $row) {
        $result = C::t('#eagle#eagle_record')->update_item_metadata($row['rid'], $row['appid'], array('isDeleted' => $deleted));
        if (!$result['success']) return $result;
        if ($deleted) C::t('pichome_resources')->recycle_data_by_rids(array($row['rid']));
        else C::t('pichome_resources')->recover_file_by_rids(array($row['rid']));
    }
    return array('success' => true);
}

if($operation == 'deletefile'){//删除文件到回收站
    $rids = isset($_GET['rids']) ? trim($_GET['rids']):'';
    $rids = array_values(array_filter(explode(',',$rids)));
    if (!$rids) exit(json_encode(array('success'=>false,'msg'=>lang('params_error'))));
    exit(json_encode(pichome_set_deleted_state($rids, true)));
}elseif($operation == 'removefolder'){//将文件从目录中移除
    $fid = isset($_GET['fid']) ? trim($_GET['fid']):'';
    $rids = isset($_GET['rids']) ? trim($_GET['rids']):'';
    $rids = explode(',',$rids);
    $folder = C::t('pichome_folder')->fetch($fid);
    if (!$folder || !C::t('pichome_vappmember')->checkuserperm_by_appid($folder['appid'])) {
        exit(json_encode(array('success' => false, 'msg' => lang('no_perm'))));
    }
    foreach (array_unique(array_filter($rids)) as $rid) {
        $resource = C::t('pichome_resources')->fetch($rid);
        if (!$resource || $resource['appid'] !== $folder['appid']) {
            exit(json_encode(array('success' => false, 'msg' => lang('params_error'))));
        }
        $nextFids = array_values(array_diff(array_filter(explode(',', $resource['fids'])), array($fid)));
        $result = C::t('#eagle#eagle_record')->update_item_metadata($rid, $resource['appid'], array('fids' => $nextFids));
        if (!$result['success']) exit(json_encode($result));
        C::t('pichome_folderresources')->delete_by_ridfid(array($rid), $fid);
    }
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
    exit(json_encode(pichome_set_deleted_state($rids, false)));
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

<?php
/*
 * @copyright   QiaoQiaoShiDai Internet Technology(Shanghai)Co.,Ltd
 * @license     https://www.oaooa.com/licenses/
 *
 * @link        https://www.oaooa.com
 * @author      zyx(zyx@oaooa.com)
 */
if(!defined('IN_OAOOA')) {
    exit('Access Denied');
}

class table_eagle_record extends dzz_table
{
    public function __construct() {
        $this->_table = 'eagle_record';
        $this->_pk    = 'id';
        parent::__construct();
    }

    public function insert_data($eid,$setarr){
            if(C::t('pichome_resources')->insert($setarr)){
                $setarr1['rid'] = $setarr['rid'];
                $setarr1['appid'] = $setarr['appid'];
                $setarr1['eid'] = $eid;
                $setarr1['dateline']=$setarr['lastdate'];
                $id = DB::result_first("select id from %t where eid = %s and appid = %s",array($this->_table,$eid,$setarr['appid']));
                if($id){
                    parent::update($id,$setarr1);
                }else{
                    parent::insert($setarr1);
                }
            }
            return true;

    }
    public function delete_by_appid($appid){
        DB::delete($this->_table,array('appid'=>$appid));
    }
    public function delete_by_rids($rids){
        if (!is_array($rids)) $rids = (array)$rids;
        DB::delete($this->_table,'rid in ('.dimplode($rids).')');
    }
    public function fetch_by_eid($eid,$appid){
        return DB::fetch_first("select * from %t where eid = %s and appid = %s",array($this->_table,$eid,$appid));
    }

    public function fetch_eid_by_rid($rid,$appid){
        return DB::result_first("select eid from %t where rid = %s",array($this->_table,$rid,$appid));
    }

    public function update_item_metadata($rid, $appid, $changes)
    {
        $app = C::t('pichome_vapp')->fetch($appid);
        if (!$app) return array('success' => false, 'msg' => '素材库不存在');
        if (intval($app['type']) !== 0) return array('success' => true);
        if (!C::t('pichome_vappmember')->checkuserperm_by_appid($appid)) {
            return array('success' => false, 'msg' => '没有素材库写入权限');
        }
        $update = array();
        if (isset($changes['fids'])) {
            $update['folders'] = array();
            foreach (array_unique(array_filter((array)$changes['fids'])) as $fid) {
                $mapping = C::t('#eagle#eagle_folderrecord')->ensure_folder_mapping($appid, $fid, $app);
                if (!empty($mapping['error'])) return array('success' => false, 'msg' => $mapping['error']);
                $update['folders'][] = strval($mapping['efid']);
            }
        }
        if (isset($changes['isDeleted'])) $update['isDeleted'] = (bool)$changes['isDeleted'];
        $eid = $this->fetch_eid_by_rid($rid, $appid);
        $library = realpath($app['path']);
        if (!$library || !$eid || !preg_match('/^[A-Z0-9]+$/i', $eid)) {
            return array('success' => false, 'msg' => 'Eagle 素材记录无效');
        }
        $images = realpath($library . DIRECTORY_SEPARATOR . 'images');
        $item = realpath($library . DIRECTORY_SEPARATOR . 'images' . DIRECTORY_SEPARATOR . $eid . '.info');
        if (!$images || !$item || strpos($item . DIRECTORY_SEPARATOR, $images . DIRECTORY_SEPARATOR) !== 0) {
            return array('success' => false, 'msg' => 'Eagle 素材路径无效');
        }
        $metadataFile = $item . DIRECTORY_SEPARATOR . 'metadata.json';
        $mtimeFile = $library . DIRECTORY_SEPARATOR . 'mtime.json';
        $locks = array();
        $original = false;
        $written = false;
        try {
            foreach (array($metadataFile, $mtimeFile) as $filename) {
                if (is_link($filename)) throw new Exception('Eagle 元数据不能是符号链接');
                $lock = @fopen($filename . '.lock', 'c+');
                if (!$lock) throw new Exception('无法打开 Eagle 元数据锁');
                $locks[] = $lock;
                @chmod($filename . '.lock', 0664);
                if (!flock($lock, LOCK_EX)) throw new Exception('无法锁定 Eagle 元数据');
            }
            $original = @file_get_contents($metadataFile);
            $metadata = $original !== false ? json_decode($original, true) : null;
            if (!is_array($metadata)) throw new Exception('Eagle metadata.json 格式错误');
            $mtime = is_file($mtimeFile) ? json_decode(file_get_contents($mtimeFile), true) : array();
            if (!is_array($mtime)) throw new Exception('Eagle mtime.json 格式错误');
            $now = (int)round(microtime(true) * 1000);
            $metadata = array_merge($metadata, $update, array('mtime' => $now, 'modificationTime' => $now, 'lastModified' => $now));
            $mtime[$eid] = $now;
            $mtime['all'] = isset($mtime['all']) ? intval($mtime['all']) + 1 : 1;
            $flags = JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES;
            if (!$this->write_metadata_file($metadataFile, json_encode($metadata, $flags))) {
                throw new Exception('无法写入 Eagle 素材信息');
            }
            $written = true;
            if (!$this->write_metadata_file($mtimeFile, json_encode($mtime, $flags))) {
                throw new Exception('无法写入 Eagle 素材变更通知');
            }
            return array('success' => true);
        } catch (Exception $error) {
            if ($written && !$this->write_metadata_file($metadataFile, $original)) {
                runlog('eagle_sync', 'Failed to restore metadata for rid ' . $rid);
            }
            return array('success' => false, 'msg' => $error->getMessage());
        } finally {
            foreach (array_reverse($locks) as $lock) {
                flock($lock, LOCK_UN);
                fclose($lock);
            }
        }
    }

    private function write_metadata_file($filename, $data)
    {
        if ($data === false) return false;
        $temporary = tempnam(dirname($filename), '.pichome-state-');
        if (!$temporary) return false;
        $saved = @file_put_contents($temporary, $data, LOCK_EX) === strlen($data);
        if ($saved) {
            @chmod($temporary, 0664);
            $saved = @rename($temporary, $filename);
        }
        if (!$saved) @unlink($temporary);
        return $saved;
    }


}

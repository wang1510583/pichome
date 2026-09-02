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

class table_eagle_folderrecord extends dzz_table
{
    public function __construct()
    {
        $this->_table = 'eagle_folderrecord';
        $this->_pk = 'id';
        parent::__construct();
    }

    /**
     * Ensure that a Pichome folder in a local Eagle library has a matching
     * Eagle folder node and mapping record. This is used by web-created
     * folders, which otherwise only exist in the Pichome database and are
     * removed again by the next Eagle index refresh.
     */
    public function ensure_folder_mapping($appid, $fid, $appdata = array())
    {
        $seen = array();
        return $this->ensure_folder_mapping_internal($appid, $fid, $appdata, $seen);
    }

    private function ensure_folder_mapping_internal($appid, $fid, $appdata, &$seen)
    {
        $appid = trim(strval($appid));
        $fid = trim(strval($fid));
        if ($appid === '' || $fid === '') {
            return array('error' => 'Eagle 文件夹参数无效');
        }
        if (isset($seen[$fid])) {
            return array('error' => '检测到循环的分类层级，无法创建 Eagle 文件夹');
        }
        if (count($seen) >= 100) {
            return array('error' => '分类层级过深，无法创建 Eagle 文件夹');
        }
        $seen[$fid] = true;

        $folder = DB::fetch_first(
            'select fid,fname,pfid,`level` from %t where fid=%s and appid=%s',
            array('pichome_folder', $fid, $appid)
        );
        if (!$folder) {
            unset($seen[$fid]);
            return array('error' => '当前分类不存在');
        }

        if (!$appdata || empty($appdata['appid'])) {
            $appdata = C::t('pichome_vapp')->fetch($appid);
        }
        if (!$appdata || intval($appdata['type']) !== 0) {
            unset($seen[$fid]);
            return array('error' => '当前素材库不是 Eagle 素材库');
        }

        $parentEfid = '';
        if (!empty($folder['pfid'])) {
            $parent = $this->ensure_folder_mapping_internal($appid, $folder['pfid'], $appdata, $seen);
            if (!empty($parent['error'])) {
                unset($seen[$fid]);
                return $parent;
            }
            $parentEfid = $parent['efid'];
        }

        $library = realpath($appdata['path']);
        if (!$library || !is_dir($library) || !is_writable($library)) {
            unset($seen[$fid]);
            return array('error' => 'Eagle 素材库目录不可写');
        }
        $metadatafile = $library . DIRECTORY_SEPARATOR . 'metadata.json';
        if (!is_file($metadatafile) || !is_readable($metadatafile) || !is_writable($metadatafile)) {
            unset($seen[$fid]);
            return array('error' => 'Eagle metadata.json 不可读写');
        }

        $lockfile = $metadatafile . '.lock';
        $lock = @fopen($lockfile, 'c+');
        if ($lock) @chmod($lockfile, 0664);
        if (!$lock || !flock($lock, LOCK_EX)) {
            if ($lock) fclose($lock);
            unset($seen[$fid]);
            return array('error' => '无法锁定 Eagle 文件夹信息');
        }

        $insertedMappingId = 0;
        $result = array();
        try {
            clearstatcache(true, $metadatafile);
            $raw = @file_get_contents($metadatafile);
            $metadata = ($raw !== false) ? json_decode($raw, true) : null;
            if (!is_array($metadata) || !isset($metadata['folders']) || !is_array($metadata['folders'])) {
                throw new Exception('Eagle metadata.json 格式错误');
            }

            // Re-query while holding the file lock so concurrent uploads to
            // the same new folder cannot create duplicate Eagle folders.
            $mapping = DB::fetch_first(
                'select id,efid from %t where fid=%s and appid=%s order by id asc',
                array($this->_table, $fid, $appid)
            );
            $efid = $mapping ? trim(strval($mapping['efid'])) : '';
            $nodeExists = $efid !== '' && $this->eagle_folder_exists($metadata['folders'], $efid);

            if (!$nodeExists) {
                if ($parentEfid !== '' && !$this->eagle_folder_exists($metadata['folders'], $parentEfid)) {
                    throw new Exception('对应的 Eagle 上级文件夹不存在');
                }
                if ($efid === '') {
                    $efid = $this->create_eagle_folder_id($metadata['folders'], $appid);
                    if ($efid === '') throw new Exception('无法生成 Eagle 文件夹 ID');
                    $insertedMappingId = intval(parent::insert(array(
                        'appid' => $appid,
                        'efid' => $efid,
                        'fid' => $fid
                    ), true));
                    if (!$insertedMappingId) throw new Exception('无法保存 Eagle 文件夹映射');
                }

                $nowms = (int) round(microtime(true) * 1000);
                $node = array(
                    'id' => $efid,
                    'name' => strval($folder['fname']),
                    'description' => '',
                    'children' => array(),
                    'modificationTime' => $nowms,
                    'tags' => array(),
                    'password' => '',
                    'passwordTips' => ''
                );
                if (!$this->append_eagle_folder($metadata['folders'], $parentEfid, $node)) {
                    throw new Exception('无法写入对应的 Eagle 文件夹');
                }
                $metadata['modificationTime'] = $nowms;
                $encoded = json_encode($metadata, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
                if ($encoded === false) throw new Exception('无法编码 Eagle 文件夹信息');

                $tmpfile = $metadatafile . '.tmp.' . getmypid() . '.' . random(6);
                if (@file_put_contents($tmpfile, $encoded, LOCK_EX) === false) {
                    @unlink($tmpfile);
                    throw new Exception('无法写入 Eagle 文件夹信息');
                }
                @chmod($tmpfile, 0664);
                if (!@rename($tmpfile, $metadatafile)) {
                    @unlink($tmpfile);
                    throw new Exception('无法更新 Eagle 文件夹信息');
                }
                @chmod($metadatafile, 0664);
            }

            $result = array(
                'fid' => $fid,
                'level' => intval($folder['level']),
                'efid' => $efid
            );
        } catch (Exception $e) {
            if ($insertedMappingId) parent::delete($insertedMappingId);
            $result = array('error' => $e->getMessage());
        }

        flock($lock, LOCK_UN);
        fclose($lock);
        unset($seen[$fid]);
        return $result;
    }

    private function eagle_folder_exists($folders, $efid)
    {
        foreach ((array)$folders as $folder) {
            if (isset($folder['id']) && strval($folder['id']) === strval($efid)) return true;
            if (!empty($folder['children']) && is_array($folder['children']) &&
                $this->eagle_folder_exists($folder['children'], $efid)) return true;
        }
        return false;
    }

    private function append_eagle_folder(&$folders, $parentEfid, $node)
    {
        if ($parentEfid === '') {
            $folders[] = $node;
            return true;
        }
        foreach ($folders as &$folder) {
            if (isset($folder['id']) && strval($folder['id']) === strval($parentEfid)) {
                if (!isset($folder['children']) || !is_array($folder['children'])) $folder['children'] = array();
                $folder['children'][] = $node;
                unset($folder);
                return true;
            }
            if (!empty($folder['children']) && is_array($folder['children']) &&
                $this->append_eagle_folder($folder['children'], $parentEfid, $node)) {
                unset($folder);
                return true;
            }
        }
        unset($folder);
        return false;
    }

    private function create_eagle_folder_id($folders, $appid)
    {
        $alphabet = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ';
        for ($attempt = 0; $attempt < 100; $attempt++) {
            $efid = '';
            for ($i = 0; $i < 13; $i++) $efid .= $alphabet[random_int(0, 35)];
            if ($this->eagle_folder_exists($folders, $efid)) continue;
            if (DB::result_first('select id from %t where efid=%s and appid=%s', array($this->_table, $efid, $appid))) continue;
            return $efid;
        }
        return '';
    }

    public function createfidby_efidappid($efid, $appid)
    {

        //检查是否有旧版数据记录值
        $fid = $efid . $appid;
        if (!DB::result_first("select fid from %t where fid = %s and appid = %s", array('pichome_folder', $fid, $appid))) {
            $fid = C::t('pichome_folder')->createfidbyappid($appid);
        }
        $setarr = [
            'appid' => $appid,
            'efid' => $efid,
            'fid' => $fid
        ];
        parent::insert($setarr);
        return $fid;
    }

    /* public function insert_folderdata_by_appid($appid, $folderdata, $perm = 0, $pfid = '', $pathkey = '', $fids = [])
     {

         foreach ($folderdata as $k => $v) {
             $id = $v['id'];
             $setarr = [
                 'fname' => $v['name'],
                 'dateline' => TIMESTAMP,
                 'pfid' => $pfid,
                 'appid' => $appid,
                 'password' => $v['password'],
                 'passwordtips' => $v['passwordTips'],
                 'disp' => $k,
             ];
             //获取或生成记录fid值
             if (!$fid = DB::result_first("select fid from %t where efid = %s and appid = %s", array($this->_table, $id, $appid))) {
                 $fid = $this->createfidby_efidappid($id, $appid);
                 $level = ($pfid) ? DB::result_first("select level from %t where fid = %s and appid = %s", array('pichome_folder', $pfid, $appid)) : $perm;
                 $setarr['level'] = $level;
             }
             $setarr['pathkey'] = ($pathkey) ? $pathkey . $fid : $fid;
             $setarr['fid'] = $fid;
             $fid = C::t('pichome_folder')->insert_data($setarr);
             $fids[] = $id;
             if ($v['children']) {
                 $children = $v['children'];
                 $perm = isset($setarr['level']) ? intval($setarr['level']) : $perm;
                 $fids = $this->insert_folderdata_by_appid($appid, $children, $perm, $fid, ($pathkey) ? $pathkey . $fid : $fid, $fids);
             }

         }
         return $fids;

     }*/


    public function insert_folderdata_by_appid($appid, $folderdata, $perm = 0, $pfid = '', $pathkey = '', $fids = [])
    {
        $stacks = [];
        $initialSetarr = [
            'fname' => '',
            'dateline' => TIMESTAMP,
            'pfid' => $pfid,
            'appid' => $appid,
            'disp' => '',
            'level' => $perm, // 初始化level
        ];
        foreach ($folderdata as $k => $v) {
            $id = $v['id'];
            $setarr = $initialSetarr;
            $setarr['fname'] = $v['name'];
            $setarr['disp'] = $k;
            // 获取或生成fid
            if (!$fid = DB::result_first("SELECT fid FROM %t WHERE efid = %s AND appid = %s", array($this->_table, $id, $appid))) {
                $fid = $this->createfidby_efidappid($id, $appid);
                $setarr['level'] = $perm;
            }
            $setarr['pathkey'] = ($pathkey) ? $pathkey . $fid : $fid;
            $setarr['fid'] = $fid;

            // 插入数据
            $fid = C::t('pichome_folder')->insert_data($setarr);
            $fids[] = $id;

            if ($v['children']) {
                $setarr['data'] = $v['children'];
            }
            $stacks[] = $setarr;
            unset($folderdata[$k]);
        }
        $j = 0;
        for($j = 0;$j < count($stacks);$j++){
            $currentstack = $stacks[$j];
            $stack = [];
            $stack[] = $currentstack;
            while (!empty($stack)) {
                $current = array_pop($stack); // 取出栈顶元素
                $folderdata = $current['data'];
                $pathkey = $current['pathkey'];
                $pfid = $current['fid'];
                $perm = $current['level'];
                $appid = $current['appid'];
                foreach ($folderdata as $k => $v) {
                    $setarr = [];
                    $id = $v['id'];
                    $setarr['fname'] = $v['name'];
                    $setarr['disp'] = $k;
                    $setarr['level'] = $perm;
                    $setarr['appid'] = $appid;
                    // 获取或生成fid
                    if (!$fid = DB::result_first("SELECT fid FROM %t WHERE efid = %s AND appid = %s", array($this->_table, $id, $appid))) {
                        $fid = $this->createfidby_efidappid($id, $appid);
                        if ($pfid) {
                            $setarr['level'] = DB::result_first("SELECT level FROM %t WHERE fid = %s AND appid = %s", array('pichome_folder', $pfid, $appid));
                        }
                    }
                    $setarr['pathkey'] = ($pathkey) ? $pathkey . $fid : $fid;
                    $setarr['fid'] = $fid;
                    $setarr['pfid'] = $pfid;
                    $setarr['dateline'] = TIMESTAMP;

                    // 插入数据
                    $fid = C::t('pichome_folder')->insert_data($setarr);
                    $fids[] = $id;

                    // 如果有子节点,压入栈
                    if ($v['children']) {
                        array_push($stack, [
                            'data' => $v['children'],
                            'pathkey' => $setarr['pathkey'],
                            'fid' => $fid,
                            'appid'=>$setarr['appid'],
                            'level'=>$setarr['level']
                        ]);
                    }
                }
            }
        }



        return $fids;

    }

    public function delete_by_fid($fids, $appid)
    {
        if (!is_array($fids)) $fids = (array)$fids;
        $delfid = $delid = [];
        foreach (DB::fetch_all("select fid,id from %t where fid in(%n) and appid = %s", array($this->_table, $fids, $appid)) as $v) {
            $delfid[] = $v['fid'];
            $delid[] = $v['id'];
        }
        if (!empty($delfid)) C::t('pichome_folder')->delete_by_fids($delfid);
        parent::delete($delid);
    }

    public function delete_by_ids($ids)
    {
        if (!array($ids)) $ids = (array)$ids;
        $fids = [];
        foreach (DB::fetch_all("select fid from %t where id in(%n)", array($this->_table, $ids)) as $v) {
            $fids[] = $v['fid'];
        }
        if (!empty($fids)) C::t('pichome_folder')->delete_by_fids($fids);
        parent::delete($ids);
    }

    public function delete_by_appid($appid)
    {
        DB::delete($this->_table, array('appid' => $appid));
    }

    public function fetch_fid_by_efid($efids, $appid)
    {
        $fids = [];
        foreach (DB::fetch_all("select ef.fid,f.level from %t ef 
        left join %t f on ef.fid=f.fid where ef.efid in(%n) and ef.appid = %s",
            array($this->_table, 'pichome_folder', $efids, $appid)) as $v) {
            $fids[$v['fid']] = $v['level'];
        }
        return $fids;
    }

}

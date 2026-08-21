<?php
/**
 * Local-only write adapter for the Pichome Eagle library.
 *
 * The Node bridge invokes this script as a child process. It deliberately
 * reuses Pichome's own IO::uploadStream implementation so the Eagle files and
 * Pichome database index are updated together.
 */

if (PHP_SAPI !== 'cli') {
    fwrite(STDERR, "CLI only\n");
    exit(2);
}

define('APPTYPEID', 200);
define('CURSCRIPT', 'pichome-sync');
define('DZZSCRIPT', 'index.php');
define('BASESCRIPT', 'index.php');

define('PICHOME_SYNC_CLI_ARGS', $argv);

require '/var/www/filepress/core/coreBase.php';

$dzz = C::app();
$dzz->init_user = true;
$dzz->init_setting = true;
$dzz->init_session = false;
$dzz->init_cron = false;
$dzz->init_misc = true;
$dzz->init();

function finish_json($payload, $status = 0) {
    echo json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES), "\n";
    exit($status);
}

$operation = isset(PICHOME_SYNC_CLI_ARGS[1]) ? PICHOME_SYNC_CLI_ARGS[1] : '';
$appid = isset(PICHOME_SYNC_CLI_ARGS[2]) ? trim(PICHOME_SYNC_CLI_ARGS[2]) : '';
$appdata = $appid ? DB::fetch_first('select * from %t where appid=%s', array('pichome_vapp', $appid)) : array();

if (!$appdata || intval($appdata['type']) !== 0) {
    finish_json(array('ok' => false, 'error' => 'Pichome Eagle 素材库不存在'), 3);
}

if ($operation === 'check') {
    finish_json(array(
        'ok' => true,
        'appid' => $appid,
        'path' => $appdata['path'],
        'writable' => is_writable($appdata['path']) && is_writable($appdata['path'] . DIRECTORY_SEPARATOR . 'images'),
        'ioReady' => class_exists('IO') && method_exists('IO', 'uploadStream')
    ));
}

function base64url_decode_text($value) {
    $value = strtr($value, '-_', '+/');
    $padding = strlen($value) % 4;
    if ($padding) $value .= str_repeat('=', 4 - $padding);
    return base64_decode($value, true);
}

function state_version($row, $efolders) {
    sort($efolders, SORT_STRING);
    return hash('sha256', implode(':', array(
        $row['eid'], intval($row['isdelete']), intval($row['dateline']), intval($row['lastdate']), implode(',', $efolders)
    )));
}

function load_item_state($appid, $sourceId) {
    $row = DB::fetch_first(
        'select e.eid,e.rid,r.isdelete,r.fids,r.dateline,r.lastdate from %t e inner join %t r on e.rid=r.rid where e.appid=%s and e.eid=%s',
        array('eagle_record', 'pichome_resources', $appid, $sourceId)
    );
    if (!$row) return array();
    $fids = array_values(array_filter(explode(',', strval($row['fids']))));
    $efolders = array();
    if ($fids) {
        foreach (DB::fetch_all('select efid from %t where appid=%s and fid in(%n)', array('eagle_folderrecord', $appid, $fids)) as $folder) {
            if (!empty($folder['efid'])) $efolders[] = strval($folder['efid']);
        }
    }
    sort($efolders, SORT_STRING);
    return array(
        'id' => strval($row['eid']),
        'rid' => strval($row['rid']),
        'folders' => $efolders,
        'isDeleted' => boolval($row['isdelete']),
        'modifiedAt' => max(intval($row['dateline']), intval($row['lastdate']) * 1000),
        'version' => state_version($row, $efolders),
        '_row' => $row,
        '_fids' => $fids
    );
}

if ($operation === 'state') {
    $items = array();
    foreach (DB::fetch_all(
        'select e.eid from %t e inner join %t r on e.rid=r.rid where e.appid=%s',
        array('eagle_record', 'pichome_resources', $appid)
    ) as $entry) {
        $state = load_item_state($appid, $entry['eid']);
        unset($state['rid'], $state['_row'], $state['_fids']);
        if ($state) $items[] = $state;
    }
    finish_json(array('ok' => true, 'items' => $items));
}

if ($operation === 'reconcile') {
    $changed = 0;
    $changedIds = array();
    $jsonflags = JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES;
    foreach (DB::fetch_all(
        'select e.eid from %t e inner join %t r on e.rid=r.rid where e.appid=%s',
        array('eagle_record', 'pichome_resources', $appid)
    ) as $entry) {
        $sourceId = strval($entry['eid']);
        $state = load_item_state($appid, $sourceId);
        if (!$state) continue;
        $metadatafile = rtrim($appdata['path'], '/\\') . DIRECTORY_SEPARATOR . 'images' . DIRECTORY_SEPARATOR . $sourceId . '.info' . DIRECTORY_SEPARATOR . 'metadata.json';
        if (!is_file($metadatafile) || !is_writable($metadatafile)) continue;
        $lock = @fopen($metadatafile . '.lock', 'c+');
        if (!$lock || !flock($lock, LOCK_EX)) { if ($lock) fclose($lock); continue; }
        $metadata = json_decode(file_get_contents($metadatafile), true);
        if (!is_array($metadata)) { flock($lock, LOCK_UN); fclose($lock); continue; }
        $currentFolders = array_values(array_unique(array_map('strval', is_array($metadata['folders'] ?? null) ? $metadata['folders'] : array())));
        $desiredFolders = array_values(array_unique(array_map('strval', $state['folders'])));
        sort($currentFolders, SORT_STRING);
        sort($desiredFolders, SORT_STRING);
        $desiredDeleted = boolval($state['isDeleted']);
        if ($currentFolders !== $desiredFolders || boolval($metadata['isDeleted'] ?? false) !== $desiredDeleted) {
            $nowms = (int) round(microtime(true) * 1000);
            $metadata['folders'] = $desiredFolders;
            $metadata['isDeleted'] = $desiredDeleted;
            $metadata['mtime'] = $nowms;
            $metadata['modificationTime'] = $nowms;
            $metadata['lastModified'] = $nowms;
            $tmpfile = $metadatafile . '.tmp.' . getmypid();
            $saved = file_put_contents($tmpfile, json_encode($metadata, $jsonflags), LOCK_EX) !== false && @rename($tmpfile, $metadatafile);
            if (!$saved) @unlink($tmpfile);
            else { @chmod($metadatafile, 0664); $changed += 1; $changedIds[] = $sourceId; }
        }
        flock($lock, LOCK_UN);
        fclose($lock);
    }
    if ($changed) {
        $mtimefile = rtrim($appdata['path'], '/\\') . DIRECTORY_SEPARATOR . 'mtime.json';
        $mtimelock = @fopen($mtimefile . '.lock', 'c+');
        if ($mtimelock && flock($mtimelock, LOCK_EX)) {
            $mtimedata = is_file($mtimefile) ? json_decode(file_get_contents($mtimefile), true) : array();
            if (!is_array($mtimedata)) $mtimedata = array();
            $nowms = (int) round(microtime(true) * 1000);
            foreach ($changedIds as $sourceId) $mtimedata[$sourceId] = $nowms;
            $mtimedata['all'] = isset($mtimedata['all']) ? intval($mtimedata['all']) + 1 : 1;
            $mtimetmp = $mtimefile . '.tmp.' . getmypid();
            if (file_put_contents($mtimetmp, json_encode($mtimedata, $jsonflags), LOCK_EX) !== false) @rename($mtimetmp, $mtimefile);
            else @unlink($mtimetmp);
            @chmod($mtimefile, 0664);
            flock($mtimelock, LOCK_UN);
        }
        if ($mtimelock) fclose($mtimelock);
    }
    finish_json(array('ok' => true, 'changed' => $changed, 'items' => $changedIds));
}

if ($operation === 'mutate') {
    $sourceId = isset(PICHOME_SYNC_CLI_ARGS[3]) ? trim(PICHOME_SYNC_CLI_ARGS[3]) : '';
    $encoded = isset(PICHOME_SYNC_CLI_ARGS[4]) ? PICHOME_SYNC_CLI_ARGS[4] : '';
    $payloadText = base64url_decode_text($encoded);
    $payload = $payloadText !== false ? json_decode($payloadText, true) : array();
    if (!preg_match('/^[A-Za-z0-9_-]+$/', $sourceId) || !is_array($payload)) {
        finish_json(array('ok' => false, 'error' => '变更参数无效'), 2);
    }
    $state = load_item_state($appid, $sourceId);
    if (!$state) finish_json(array('ok' => false, 'error' => '素材不存在'), 4);
    if (!empty($payload['baseVersion']) && !hash_equals($state['version'], strval($payload['baseVersion']))) {
        unset($state['rid'], $state['_row'], $state['_fids']);
        finish_json(array('ok' => false, 'conflict' => true, 'error' => '远端素材已发生变化', 'state' => $state), 5);
    }

    $itemdir = rtrim($appdata['path'], '/\\') . DIRECTORY_SEPARATOR . 'images' . DIRECTORY_SEPARATOR . $sourceId . '.info';
    $metadatafile = $itemdir . DIRECTORY_SEPARATOR . 'metadata.json';
    if (!is_file($metadatafile) || !is_writable($metadatafile)) {
        finish_json(array('ok' => false, 'error' => 'Eagle metadata.json 不可写'), 4);
    }
    $lock = @fopen($metadatafile . '.lock', 'c+');
    if (!$lock || !flock($lock, LOCK_EX)) finish_json(array('ok' => false, 'error' => '无法锁定素材信息'), 4);
    $metadata = json_decode(file_get_contents($metadatafile), true);
    if (!is_array($metadata)) {
        flock($lock, LOCK_UN); fclose($lock);
        finish_json(array('ok' => false, 'error' => '素材信息格式错误'), 4);
    }

    $nextDeleted = array_key_exists('isDeleted', $payload) ? boolval($payload['isDeleted']) : $state['isDeleted'];
    $nextEfolders = $state['folders'];
    if (array_key_exists('folders', $payload)) {
        $nextEfolders = array_values(array_unique(array_filter(array_map('strval', is_array($payload['folders']) ? $payload['folders'] : array()))));
        sort($nextEfolders, SORT_STRING);
    }
    $nextFids = array();
    if ($nextEfolders) {
        foreach (DB::fetch_all('select fid from %t where appid=%s and efid in(%n)', array('eagle_folderrecord', $appid, $nextEfolders)) as $folder) {
            if (!empty($folder['fid'])) $nextFids[] = strval($folder['fid']);
        }
    }
    $nextFids = array_values(array_unique($nextFids));
    $nowms = (int) round(microtime(true) * 1000);
    $metadata['folders'] = $nextEfolders;
    $metadata['isDeleted'] = $nextDeleted;
    $metadata['mtime'] = $nowms;
    $metadata['modificationTime'] = $nowms;
    $metadata['lastModified'] = $nowms;
    $tmpfile = $metadatafile . '.tmp.' . getmypid();
    $jsonflags = JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES;
    $saved = file_put_contents($tmpfile, json_encode($metadata, $jsonflags), LOCK_EX) !== false && @rename($tmpfile, $metadatafile);
    if (!$saved) @unlink($tmpfile);
    @chmod($metadatafile, 0664);
    flock($lock, LOCK_UN); fclose($lock);
    if (!$saved) finish_json(array('ok' => false, 'error' => '无法保存素材变更'), 4);

    $oldFids = $state['_fids'];
    $removedFids = array_values(array_diff($oldFids, $nextFids));
    $addedFids = array_values(array_diff($nextFids, $oldFids));
    if (!$state['isDeleted']) {
        if ($removedFids) C::t('pichome_folder')->add_filenum_by_fid($removedFids, -1);
        if ($addedFids) C::t('pichome_folder')->add_filenum_by_fid($addedFids, 1);
    }
    DB::delete('pichome_folderresources', array('rid' => $state['rid']));
    foreach ($nextFids as $fid) {
        $pathkey = DB::result_first('select pathkey from %t where fid=%s', array('pichome_folder', $fid));
        DB::insert('pichome_folderresources', array('appid' => $appid, 'rid' => $state['rid'], 'fid' => $fid, 'pathkey' => $pathkey));
    }
    if (!$state['isDeleted'] && $nextDeleted) C::t('pichome_resources')->recycle_data_by_rids(array($state['rid']));
    elseif ($state['isDeleted'] && !$nextDeleted) C::t('pichome_resources')->recover_file_by_rids(array($state['rid']));
    DB::update('pichome_resources', array(
        'fids' => implode(',', $nextFids),
        'isdelete' => $nextDeleted ? 1 : 0,
        'dateline' => $nowms,
        'mtime' => $nowms,
        'lastdate' => time()
    ), array('rid' => $state['rid']));

    $mtimefile = rtrim($appdata['path'], '/\\') . DIRECTORY_SEPARATOR . 'mtime.json';
    $mtimelock = @fopen($mtimefile . '.lock', 'c+');
    if ($mtimelock && flock($mtimelock, LOCK_EX)) {
        $mtimedata = is_file($mtimefile) ? json_decode(file_get_contents($mtimefile), true) : array();
        if (!is_array($mtimedata)) $mtimedata = array();
        $mtimedata[$sourceId] = $nowms;
        $mtimedata['all'] = isset($mtimedata['all']) ? intval($mtimedata['all']) + 1 : 1;
        $mtimetmp = $mtimefile . '.tmp.' . getmypid();
        if (file_put_contents($mtimetmp, json_encode($mtimedata, $jsonflags), LOCK_EX) !== false) @rename($mtimetmp, $mtimefile);
        else @unlink($mtimetmp);
        @chmod($mtimefile, 0664);
        flock($mtimelock, LOCK_UN); fclose($mtimelock);
    } elseif ($mtimelock) fclose($mtimelock);

    $nextState = load_item_state($appid, $sourceId);
    unset($nextState['rid'], $nextState['_row'], $nextState['_fids']);
    finish_json(array('ok' => true, 'state' => $nextState));
}

if ($operation !== 'upload') {
    finish_json(array('ok' => false, 'error' => '不支持的操作'), 2);
}

$file = isset(PICHOME_SYNC_CLI_ARGS[3]) ? PICHOME_SYNC_CLI_ARGS[3] : '';
$filename = isset(PICHOME_SYNC_CLI_ARGS[4]) ? PICHOME_SYNC_CLI_ARGS[4] : '';
$eagleFolderId = isset(PICHOME_SYNC_CLI_ARGS[5]) ? trim(PICHOME_SYNC_CLI_ARGS[5]) : '';

if (!$file || !is_file($file) || !$filename) {
    finish_json(array('ok' => false, 'error' => '上传临时文件无效'), 2);
}

$pfid = '';
if ($eagleFolderId) {
    $folder = DB::fetch_first(
        'select fid from %t where appid=%s and efid=%s',
        array('eagle_folderrecord', $appid, $eagleFolderId)
    );
    if ($folder && !empty($folder['fid'])) $pfid = $folder['fid'];
}

$before = array();
$imagesdir = rtrim($appdata['path'], '/\\') . DIRECTORY_SEPARATOR . 'images';
foreach (glob($imagesdir . DIRECTORY_SEPARATOR . '*.info', GLOB_ONLYDIR) ?: array() as $dir) {
    $before[basename($dir, '.info')] = true;
}

$result = IO::uploadStream($file, $filename, $appid, $pfid, '', array());
if (!$result || !empty($result['error'])) {
    finish_json(array('ok' => false, 'error' => !empty($result['error']) ? $result['error'] : 'Pichome 写入失败'), 4);
}

$createdId = '';
if (!empty($result['icoarr'][0]['rid'])) {
    $createdRecord = DB::fetch_first(
        'select eid from %t where rid=%s and appid=%s',
        array('eagle_record', $result['icoarr'][0]['rid'], $appid)
    );
    if ($createdRecord && !empty($createdRecord['eid'])) $createdId = strval($createdRecord['eid']);
}
foreach (glob($imagesdir . DIRECTORY_SEPARATOR . '*.info', GLOB_ONLYDIR) ?: array() as $dir) {
    if ($createdId) break;
    $id = basename($dir, '.info');
    if (!isset($before[$id])) {
        $createdId = $id;
        break;
    }
}

if (!$createdId && !empty($result['icoarr'][0]['eid'])) $createdId = strval($result['icoarr'][0]['eid']);
finish_json(array('ok' => true, 'sourceId' => $createdId, 'folderMapped' => $pfid !== ''));

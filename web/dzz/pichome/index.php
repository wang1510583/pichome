<?php
if (!defined('IN_OAOOA')) {
    exit('Access Denied');
}
// 禁止缓存(2026-08-19): 避免安卓 WebView 缓存旧页面导致长按菜单等更新不生效
header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
header('Pragma: no-cache');
header('Expires: 0');
//检查登录
Hook::listen('check_login');
global $_G;
$uid = $_G['uid'];
if(!C::t('pichome_vappmember')->checkuserperm_by_uid($uid)){
    exit(json_encode(array('success'=>false,'msg'=>lang('no_perm'))));
}
@include Hook::listen('mod_start',$_GET,null,true);//模块路由
exit();
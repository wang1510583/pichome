const comavatar = {
    props:{
        level:{//密级
            type: Number,
        },
        iscollect:{//是否显示收藏夹
            type: Boolean,
        },
        adminid:{//是否为管理员
            type: Number,
        },
        PICHOME_LIENCE:{//是否开启多人
            type: Boolean,
        },
        uid:{//是否登入
            type: Number,
        },
        upgrade:{//是否有通知
            type: String,
        },
        version:{//版本信息
            type: String,
        },
        formhash:{
            type: String,
        },
        isrefresh:{
            required:false,
            type: Boolean,
            default:false,
        },
    },
    template:`
        <template v-if="isuid">
            <el-dropdown trigger="click" @command="handlecommand"  v-cloak>
                <div class="dzz_avatar_img">
                    <slot></slot>
                    <template v-if="level">
                        <template v-for="item in LevelData">
                            <el-tooltip v-if="item.val == level" class="level" effect="dark" :content="Lang.text1" placement="bottom-end">
                                <el-image v-cloak class="level-image" :src="item.img" fit="contain"></el-image>
                            </el-tooltip>
                        </template>
                    </template>
                </div>
                <template #dropdown>
                    <el-dropdown-menu slot="dropdown" style="width: 165px;">
                        <el-dropdown-item command="personal"><a href="user.php?mod=my" >{{Lang.text2}}</a></el-dropdown-item>
                        <el-dropdown-item command="manage" v-if="adminid==1"><a href="index.php?mod=manage">{{Lang.text3}}</a></el-dropdown-item>
                        <el-dropdown-item id="pichome-android-cache-menu-item" command="pichomeCache">{{Lang.textCache || '查看缓存'}}</el-dropdown-item>
                        <el-divider class="adjust-divider"></el-divider>
						<el-dropdown-item command="about">{{Lang.text4}}</el-dropdown-item>
                        <el-dropdown-item command="OutLogin">{{Lang.text5}}</el-dropdown-item>
                    </el-dropdown-menu>
                </template>
            </el-dropdown>
        </template>
        <template v-else>
            <el-button type="primary" size="mini" @click="GoLogin">{{Lang.text6}}</el-button>
        </template>
        <el-dialog
            v-model="CacheData.visible"
            title="素材与浏览器缓存状态"
            width="480px"
            @open="CacheOpen">
            <div class="cacheBox" style="padding:10px 15px;" v-loading="CacheData.loading">
                <div class="el-form el-form--default el-form--label-left">
                    <div class="el-form-item" style="margin-bottom: 14px;">
                        <label class="el-form-item__label" style="width:120px;">当前素材缓存：</label>
                        <div class="el-form-item__content" style="line-height: 32px;">
                            <span style="font-weight: bold; color: #67C23A; font-size: 15px;">{{CacheData.hitCount}} / {{CacheData.totalCount}}</span>
                            <span style="color: #606266; font-size: 13px; margin-left: 8px;">
                                (命中率 <b :style="{color: CacheData.hitRate > 0 ? '#67C23A' : '#909399'}">{{CacheData.hitRate}}%</b>，已节省 <b style="color: #409EFF;">{{CacheData.savedText}}</b> 流量)
                            </span>
                        </div>
                    </div>
                    <div class="el-form-item" style="margin-bottom: 14px;">
                        <label class="el-form-item__label" style="width:120px;">加速机制：</label>
                        <div class="el-form-item__content" style="color: #606266; font-size: 13px; line-height: 32px;">
                            HTTP 强缓存 (30天私有缓存) + 协商缓存 (304)
                        </div>
                    </div>
                    <div class="el-form-item" style="margin-bottom: 16px;">
                        <label class="el-form-item__label" style="width:120px;">缓存说明：</label>
                        <div class="el-form-item__content" style="color: #909399; font-size: 12px; line-height: 1.6;">
                            图片与视频由浏览器底层 Disk Cache 强缓存加速。只要素材未被修改，调整分类、移动目录或重载页面均直接秒开，无需重复下载。
                        </div>
                    </div>
                </div>
                <div style="margin-top: 15px; display: flex; justify-content: flex-end; gap: 10px;">
                    <el-button size="small" type="danger" plain :loading="CacheData.clearing" @click="ClearWebCache">清理本地缓存</el-button>
                    <el-button size="small" type="primary" plain @click="ForceReload">强制刷新</el-button>
                    <el-button size="small" @click="CacheData.visible = false">关闭</el-button>
                </div>
            </div>
        </el-dialog>
        <el-dialog
            v-model="AboutData.visible"
            title=""
            @open="AboutOpen">
            <div class="aboutBox" style="padding:15px;">
                <div class="el-image" style="width: 100%;height: 150px;margin-bottom: 16px;">
                    <img :src="AboutData.data.logo" alt="" class="el-image__inner" style="object-fit: contain;">
                </div>
                <div class="el-form el-form--default el-form--label-left">
                    <div class="el-form-item" style="margin-bottom: 0;">
                        <label class="el-form-item__label" style="width:100px;">{{Lang.text7}}：</label>
                        <div class="el-form-item__content">{{AboutData.data.name}}</div>
                    </div>
                    <div class="el-form-item" style="margin-bottom: 0;">
                        <label class="el-form-item__label" style="width:100px;">{{Lang.text8}}：</label>
                        <div class="el-form-item__content">{{AboutData.data.version}}</div>
                    </div>
                    <div class="el-form-item" style="margin-bottom: 0;">
                        <label class="el-form-item__label" style="width:100px;">{{Lang.text9}}：</label>
                        <div class="el-form-item__content">
                            <span class="el-text">{{AboutData.data.copyright}}</span>
                        </div>
                    </div>
                    <div class="el-form-item" style="margin-bottom: 0;">
                        <label class="el-form-item__label" style="width:100px;">{{Lang.text10}}：</label>
                        <div class="el-form-item__content">
                            <a class="el-link el-link--primary" :href="AboutData.data.website" target="_blank">
                                <span class="el-link__inner">{{AboutData.data.home}}</span>
                            </a>
                        </div>
                    </div>
                </div>
            </div>
        </el-dialog>

    `,
    setup(props, context){
        let AboutData = reactive({
            visible:false,
            loading:false,
            data:{
                logo:'',
                name:'',
                version:'',
                copyright:' ',
                website:'',
                home:''
            }
        });
        let CacheData = reactive({
            visible: false,
            loading: false,
            clearing: false,
            totalCount: 0,
            hitCount: 0,
            hitRate: 0,
            savedBytes: 0,
            savedText: '0 B'
        });
        let isuid = ref(props.uid);
        let Lang = {
            text1:__lang.level,
            text2:__lang.userCenter,
            text3:__lang.system_management,
            textCache:'查看缓存',
            text4:__lang.about,
            text5:__lang.logout,
            text6:__lang.login,
            text7:__lang.software_name,
            text8:__lang.version_information,
            text9:__lang.copyright_information,
            text10:__lang.Website_address,
        };
        const LevelData = [
            {val:1,img:'static/vue_components/avatar/image/1.png'},
            {val:2,img:'static/vue_components/avatar/image/2.png'},
            {val:3,img:'static/vue_components/avatar/image/3.png'},
            {val:4,img:'static/vue_components/avatar/image/4.png'},
            {val:5,img:'static/vue_components/avatar/image/5.png'},
        ];
        function handlecommand(type){
            switch(type){
                case 'collection':
                    window.location.href = 'index.php?mod=collection';
                break;
                case 'orguser':
                    window.open('admin.php?mod=orguser');
                break;
                case 'personal':
                    window.location.href = 'user.php?mod=my';
                break;
                case 'help':
                    window.open('https://www.yuque.com/pichome');
                break;
                case 'problem':
                    window.open('https://support.qq.com/products/340252');
                break;
                case 'setting':
                    window.location.href = 'index.php?mod=pichome&op=admin&do=basic';
                break;
                case 'manage':
                    window.location.href = 'index.php?mod=manage';
                break;
                case 'tab':
                    window.location.href = 'index.php?mod=tab';
                break;
                case 'library':
                    window.location.href = 'index.php?mod=pichome&op=library';
                break;
                case 'pichomeCache':
                    if (window.PichomeCache && typeof window.PichomeCache.openSettings === 'function') {
                        try {
                            window.PichomeCache.openSettings();
                        } catch (err) {
                            CacheData.visible = true;
                        }
                    } else {
                        CacheData.visible = true;
                    }
                break;
                case 'about':
                    AboutData.visible = true;
                break;
                case 'OutLogin':
                    ElementPlus.ElMessageBox.confirm(__lang.js_exit, __lang.board_message, {
                          confirmButtonText: __lang.confirms,
                          cancelButtonText: __lang.cancel,
                          type: 'warning'
                        }).then(async function() {
                            let {data: res} = await axios.post('user.php?mod=login&op=logging&inajax=1&action=logout&formhash='+props.formhash+'&t='+new Date().getTime());
                            if(res.success){
                                window.location.reload();
                                // if(props.isrefresh){
                                //     window.location.reload();
                                // }else{
                                //     isuid.value = 0;
                                //     context.emit('handlelogout',res.formhash);
                                // }
                                
                            }else{
                                ElementPlus.ElMessage.error(res.msg || __lang.logout_error)
                            }
                        }).catch(function() {
                                   
                        });
                break;
                case 'system':
                    window.open(SITEURL+'admin.php?mod=system');
                break;
                case 'systemlog':
                    window.open(SITEURL+'admin.php?mod=systemlog');
                break;
            }
           
        }
        function GoLogin(){
            var referer = encodeURIComponent(window.location.href);
            window.location.href = 'user.php?mod=login&referer='+referer;
        }
        
        async function AboutOpen(){
            AboutData.loading = true;
            let {data: res} = await axios.post('user.php?mod=space&op=about');
            AboutData.data.logo = res.data.logo+'?'+new Date().getTime();
            AboutData.data.name = res.data.sitename;
            AboutData.data.version = res.data.version;
            AboutData.data.copyright = res.data.copyright;
            AboutData.data.website = res.data.home_page;
            AboutData.data.home = res.data.home;
        };

        function formatBytes(bytes) {
            if (!bytes || bytes <= 0) return '0 B';
            const k = 1024;
            const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
            const i = Math.floor(Math.log(bytes) / Math.log(k));
            return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
        }

        async function CacheOpen() {
            CacheData.loading = true;
            try {
                // 统计当前页面会话中的素材与静态资源缓存命中与节省流量
                let total = 0;
                let hits = 0;
                let saved = 0;
                if (window.performance && typeof performance.getEntriesByType === 'function') {
                    const resources = performance.getEntriesByType('resource');
                    for (let i = 0; i < resources.length; i++) {
                        const r = resources[i];
                        const name = r.name || '';
                        const isMedia = name.indexOf('mod=io') !== -1 || name.indexOf('static/') !== -1 || r.initiatorType === 'img' || r.initiatorType === 'video';
                        if (isMedia) {
                            total++;
                            // transferSize 为 0 或显著小于 decoded/encoded body size 表示命中浏览器缓存
                            if (r.transferSize === 0 || (r.encodedBodySize > 0 && r.transferSize < r.encodedBodySize)) {
                                hits++;
                                saved += (r.decodedBodySize || r.encodedBodySize || 0);
                            }
                        }
                    }
                }
                CacheData.totalCount = total;
                CacheData.hitCount = hits;
                CacheData.hitRate = total > 0 ? Math.round((hits / total) * 100) : 100;
                CacheData.savedBytes = saved;
                CacheData.savedText = formatBytes(saved);
            } catch (e) {
                console.error('Cache inspect error:', e);
            } finally {
                CacheData.loading = false;
            }
        }

        async function ClearWebCache() {
            CacheData.clearing = true;
            try {
                if (window.caches && caches.keys) {
                    const keys = await caches.keys();
                    await Promise.all(keys.map(key => caches.delete(key)));
                }
                await CacheOpen();
                if (typeof ElementPlus !== 'undefined' && ElementPlus.ElMessage) {
                    ElementPlus.ElMessage.success('浏览器本地素材与数据缓存已清理');
                }
            } catch (e) {
                console.error('Clear cache error:', e);
                if (typeof ElementPlus !== 'undefined' && ElementPlus.ElMessage) {
                    ElementPlus.ElMessage.error('清理缓存失败: ' + (e.message || '未知错误'));
                }
            } finally {
                CacheData.clearing = false;
            }
        }

        function ForceReload() {
            window.location.reload(true);
        }

        return {
            AboutData,
            CacheData,
            LevelData,
            handlecommand,
            isuid,
            GoLogin,
            Lang,
            AboutOpen,
            CacheOpen,
            ClearWebCache,
            ForceReload
        }
    }
};

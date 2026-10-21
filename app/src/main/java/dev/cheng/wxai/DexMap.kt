package dev.cheng.wxai

/**
 * 微信 8.0.76 的混淆符号表。
 *
 * 全部签名取自 WeKit 的 Dex-Test 滚动发布：
 * https://github.com/Ujhhgtg/WeKit/releases/download/Dex-Test/wechat-8.0.76-3141-domestic.json
 * （3120 与 3141 两个构建的 489 个公共 key 混淆名完全一致，故此处同时适用于两者）
 *
 * 换微信版本时：重新下对应版本报告，用同等 key 覆盖这里的常量即可。
 */
object DexMap {

    /** 支持的本版本 versionName */
    const val SUPPORTED_VERSION_NAME = "8.0.76"

    /** 支持的本版本 versionCode（8.0.76 的两个构建） */
    val SUPPORTED_VERSION_CODES = longArrayOf(3141L, 3120L)

    /** com.tencent.mm.storage.e9 —— MsgInfo */
    const val MSG_INFO = "com.tencent.mm.storage.e9"

    /** com.tencent.mm.storage.f9 —— MsgInfoStorage */
    const val MSG_INFO_STORAGE = "com.tencent.mm.storage.f9"

    /** f9.H9(MsgInfo): long —— 写入消息表（收/发都会走） */
    const val MSG_INSERT_NAME = "H9"

    /** f9.I9(MsgInfo, boolean): long —— API 插入消息 */
    const val MSG_API_INSERT_NAME = "I9"

    /** y11.r0 —— NetSceneSendMsg */
    const val SEND_SCENE = "y11.r0"

    /** com.tencent.mm.modelbase.r1 —— NetSceneQueue */
    const val QUEUE = "com.tencent.mm.modelbase.r1"

    /** com.tencent.mm.modelbase.m1 —— NetSceneBase */
    const val SCENE_BASE = "com.tencent.mm.modelbase.m1"

    /** e01.d9 —— 提供 static e() 返回 NetSceneQueue */
    const val QUEUE_GETTER = "e01.d9"

    const val QUEUE_GETTER_METHOD = "e"

    /** r1.g(NetSceneBase): boolean —— 把场景投进队列 */
    const val QUEUE_POST_METHOD = "g"

    // ---- MsgInfo 字段名（微信未混淆，可直接按名取）----
    const val F_TALKER = "field_talker"
    const val F_CONTENT = "field_content"
    const val F_TYPE = "field_type"
    const val F_IS_SEND = "field_isSend"
    const val F_MSG_ID = "field_msgId"
    const val F_SVR_ID = "field_msgSvrId"
    const val F_CREATE_TIME = "field_createTime"

    /** 文本消息类型码 */
    const val TYPE_TEXT = 1
}

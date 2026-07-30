package com.example.midun.data.real

/**
 * 「与密码无关」的卡层失败（`[usb]` 2026-07-30）。设备/驱动层打不开盘、卡被拔走、卡绑定了别的设备、
 * 密钥库损坏——都属于此类：**再输一次密码也不会有任何变化**。
 *
 * 为什么需要一个类型而不是看文案：登录页的 5 次尝试上限（`AuthViewModel`）到顶就是「身份认证失败，请联系
 * 技术人员」。把这类失败也计进去，用户会被自己没做错的事锁死。而 `authenticate` 的失败原因只能靠 message
 * 传给上层，靠文案匹配去分类既脆又易漏，故就地把类型带上来。
 */
class CardDeviceException(message: String) : IllegalStateException(message)

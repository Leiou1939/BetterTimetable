package com.liangyuchen.timetable.nearby

import android.content.Context
import com.liangyuchen.timetable.data.NearbyPayload

/**
 * 双向交换：一边广播自己的课表，一边扫描并连接对方。
 *
 * 两台手机都点「开始交换」，就会各自广播、各自去取对方的课表 ——
 * 不需要区分谁说发送方、谁是接收方。
 */
class NearbyExchange(private val context: Context) {

    interface Listener {
        /** 界面上的状态文字。 */
        fun onStatus(text: String)

        /** 扫到了谁（用来显示进度）。 */
        fun onPeer(name: String, connected: Boolean)

        /** 收到对方的课表。 */
        fun onPayload(payload: NearbyPayload)
    }

    private val sender = NearbySender(context)
    private val receiver = NearbyReceiver(context)
    private val dialed = HashSet<String>()

    @Volatile
    private var stopped = false

    fun start(payload: NearbyPayload, listener: Listener) {
        stopped = false
        dialed.clear()

        sender.start(
            payload,
            object : NearbySender.Listener {
                override fun onStatus(text: String) {
                    if (!stopped) listener.onStatus(text)
                }

                override fun onServed() {
                    if (!stopped) listener.onStatus("对方已取走你的课表")
                }
            },
        )

        receiver.scan(
            object : NearbyReceiver.Listener {
                override fun onStatus(text: String) {
                    if (!stopped) listener.onStatus(text)
                }

                override fun onFound(device: NearbyReceiver.DeviceItem) {
                    if (stopped) return
                    listener.onPeer(device.name, connected = false)
                    // 每台设备只连一次，避免反复连接
                    if (dialed.add(device.address)) {
                        receiver.connect(device.address)
                    }
                }

                override fun onPayload(payload: NearbyPayload) {
                    if (!stopped) listener.onPayload(payload)
                }
            },
        )
    }

    fun stop() {
        stopped = true
        sender.stop()
        receiver.stop()
    }
}

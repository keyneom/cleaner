package com.cleaner.filter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.cleaner.filter.MainActivity
import com.cleaner.filter.R
import com.cleaner.filter.settings.DnsProvider
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal DNS-only VPN: forwards DNS queries to the configured family resolver over UDP/53.
 * Does not tunnel general IP traffic (returns EINVAL for non-DNS packets).
 */
class DnsVpnService : VpnService() {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var tunFd: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        val providerName = intent?.getStringExtra(EXTRA_PROVIDER) ?: DnsProvider.CLOUDFLARE_FAMILY.name
        val provider = DnsProvider.entries.find { it.name == providerName } ?: DnsProvider.CLOUDFLARE_FAMILY
        startForegroundWithNotification()
        startVpn(provider)
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        createChannel()
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.dns_vpn_notification_title))
            .setContentText(getString(R.string.dns_vpn_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startVpn(provider: DnsProvider) {
        stopVpn()
        running.set(true)

        val builder = Builder()
            .setSession("Cleaner DNS")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("10.0.0.1")
            .addRoute("0.0.0.0", 0)

        tunFd = builder.establish()
        val dnsTarget = resolveDnsTarget(provider)

        thread = Thread {
            runLoop(tunFd!!, dnsTarget)
        }.also { it.start() }
    }

    private fun resolveDnsTarget(provider: DnsProvider): InetAddress {
        return when (provider) {
            DnsProvider.CLOUDFLARE_FAMILY -> InetAddress.getByName("1.1.1.3")
            DnsProvider.CLEANBROWSING_FAMILY -> InetAddress.getByName("185.228.168.168")
            DnsProvider.NEXTDNS -> InetAddress.getByName("45.90.28.0")
            DnsProvider.OFF -> InetAddress.getByName("1.1.1.3")
        }
    }

    private fun runLoop(tun: ParcelFileDescriptor, upstream: InetAddress) {
        val input = FileInputStream(tun.fileDescriptor)
        val output = FileOutputStream(tun.fileDescriptor)
        val buffer = ByteArray(32767)

        while (running.get()) {
            try {
                val length = input.read(buffer)
                if (length <= 0) continue
                if (length < 20) continue

                val version = (buffer[0].toInt() shr 4) and 0x0F
                if (version != 4) continue

                val protocol = buffer[9].toInt() and 0xFF
                if (protocol != 17) continue // UDP only

                val ipHeaderLen = (buffer[0].toInt() and 0x0F) * 4
                if (length < ipHeaderLen + 8) continue

                val dstPort = ((buffer[ipHeaderLen + 3].toInt() and 0xFF))
                if (dstPort != 53) continue

                val udpPayloadOffset = ipHeaderLen + 8
                val udpPayloadLen = length - udpPayloadOffset
                if (udpPayloadLen <= 0) continue

                val query = buffer.copyOfRange(udpPayloadOffset, length)
                val response = forwardDns(query, upstream) ?: continue

                val packet = buildDnsResponse(buffer, ipHeaderLen, response)
                output.write(packet)
            } catch (_: Exception) {
                if (!running.get()) break
            }
        }
    }

    private fun forwardDns(query: ByteArray, upstream: InetAddress): ByteArray? {
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 5000
                val send = DatagramPacket(query, query.size, upstream, 53)
                socket.send(send)
                val recvBuf = ByteArray(4096)
                val recv = DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(recv)
                recv.data.copyOf(recv.length)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildDnsResponse(original: ByteArray, ipHeaderLen: Int, dnsResponse: ByteArray): ByteArray {
        val udpLen = 8 + dnsResponse.size
        val totalLen = ipHeaderLen + udpLen
        val out = ByteArray(totalLen)
        System.arraycopy(original, 0, out, 0, ipHeaderLen)
        // swap src/dst IP
        for (i in 0 until 4) {
            val tmp = out[12 + i]
            out[12 + i] = out[16 + i]
            out[16 + i] = tmp
        }
        out[2] = 0
        out[3] = 0
        val lenBuf = ByteBuffer.allocate(2)
        lenBuf.putShort(totalLen.toShort())
        out[2] = lenBuf.get(0)
        out[3] = lenBuf.get(1)

        // UDP header
        out[ipHeaderLen] = original[ipHeaderLen + 2]
        out[ipHeaderLen + 1] = original[ipHeaderLen + 3]
        out[ipHeaderLen + 2] = original[ipHeaderLen]
        out[ipHeaderLen + 3] = original[ipHeaderLen + 1]
        val udpLenBuf = ByteBuffer.allocate(2)
        udpLenBuf.putShort(udpLen.toShort())
        out[ipHeaderLen + 4] = udpLenBuf.get(0)
        out[ipHeaderLen + 5] = udpLenBuf.get(1)
        out[ipHeaderLen + 6] = 0
        out[ipHeaderLen + 7] = 0

        System.arraycopy(dnsResponse, 0, out, ipHeaderLen + 8, dnsResponse.size)
        return out
    }

    private fun stopVpn() {
        running.set(false)
        thread?.interrupt()
        thread = null
        tunFd?.close()
        tunFd = null
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cleaner DNS",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val EXTRA_PROVIDER = "dns_provider"
        const val ACTION_STOP = "com.cleaner.filter.STOP_DNS"
        private const val CHANNEL_ID = "cleaner_dns"
        private const val NOTIFICATION_ID = 1002
    }
}

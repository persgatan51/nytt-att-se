package se.persgatan.nyttattse

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Minimal klient för Google TV / Android TV:s fjärrkontrollprotokoll (v2), samma protokoll som
 * Googles egen fjärrkontrollapp använder. Baserat på androidtvremote2 (tronikos).
 *
 *  - Parkoppling: TLS mot port 6467, "polo"-meddelanden. TV:n visar en 6-teckens kod.
 *  - Fjärrkontroll: TLS mot port 6466. Efter handskakning skickas en app-länk som TV:n öppnar.
 *
 * Båda portarna kräver att appen visar upp samma klientcertifikat, som skapas en gång och sparas.
 */
class TvRemote(private val ctx: Context) {

    class TvException(msg: String) : Exception(msg)

    // ---------------- Minimal protobuf ----------------
    private class Pb {
        private val out = ByteArrayOutputStream()
        fun int(field: Int, v: Long) = apply { tag(field, 0); varint(out, v) }
        fun bytes(field: Int, b: ByteArray) = apply { tag(field, 2); varint(out, b.size.toLong()); out.write(b) }
        fun str(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))
        fun msg(field: Int, m: Pb) = bytes(field, m.toBytes())
        fun toBytes(): ByteArray = out.toByteArray()
        private fun tag(field: Int, wire: Int) = varint(out, ((field shl 3) or wire).toLong())

        companion object {
            fun varint(o: OutputStream, value: Long) {
                var v = value
                while (true) {
                    if (v and 0x7FL.inv() == 0L) { o.write(v.toInt()); return }
                    o.write(((v and 0x7F) or 0x80).toInt())
                    v = v ushr 7
                }
            }

            /** Tolkar ett meddelande till fältnummer -> lista av värden (Long eller ByteArray). */
            fun parse(b: ByteArray): Map<Int, List<Any>> {
                val res = HashMap<Int, MutableList<Any>>()
                var i = 0
                fun readVarint(): Long {
                    var shift = 0; var r = 0L
                    while (true) {
                        val c = b[i++].toInt() and 0xFF
                        r = r or ((c and 0x7F).toLong() shl shift)
                        if (c and 0x80 == 0) return r
                        shift += 7
                    }
                }
                while (i < b.size) {
                    val key = readVarint()
                    val field = (key ushr 3).toInt()
                    when ((key and 7).toInt()) {
                        0 -> res.getOrPut(field) { mutableListOf() }.add(readVarint())
                        2 -> {
                            val len = readVarint().toInt()
                            res.getOrPut(field) { mutableListOf() }.add(b.copyOfRange(i, i + len)); i += len
                        }
                        1 -> i += 8
                        5 -> i += 4
                        else -> throw IOException("Okänd protobuf-typ")
                    }
                }
                return res
            }
        }
    }

    private fun Map<Int, List<Any>>.long(f: Int): Long? = this[f]?.firstOrNull() as? Long
    private fun Map<Int, List<Any>>.sub(f: Int): Map<Int, List<Any>>? =
        (this[f]?.firstOrNull() as? ByteArray)?.let { Pb.parse(it) }

    private fun send(o: OutputStream, msg: ByteArray) {
        val buf = ByteArrayOutputStream()
        Pb.varint(buf, msg.size.toLong()); buf.write(msg)
        o.write(buf.toByteArray()); o.flush()
    }

    private fun receive(input: InputStream): ByteArray {
        var shift = 0; var len = 0L
        while (true) {
            val c = input.read()
            if (c < 0) throw IOException("TV:n stängde anslutningen")
            len = len or ((c and 0x7F).toLong() shl shift)
            if (c and 0x80 == 0) break
            shift += 7
        }
        val data = ByteArray(len.toInt())
        DataInputStream(input).readFully(data)
        return data
    }

    // ---------------- Certifikat och TLS ----------------
    private val pw = "nyttattse".toCharArray()
    private val ksFile get() = File(ctx.filesDir, "atvremote.p12")

    private fun keyStore(): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        if (ksFile.exists()) {
            ksFile.inputStream().use { ks.load(it, pw) }
            return ks
        }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val name = X500Name("CN=atvremote")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(1000), Date(now - 86_400_000L),
            Date(now + 10L * 365 * 86_400_000L), name, kp.public
        )
        builder.addExtension(Extension.basicConstraints, false, BasicConstraints(0))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        ks.load(null, null)
        ks.setKeyEntry("atv", kp.private, pw, arrayOf(cert))
        ksFile.outputStream().use { ks.store(it, pw) }
        return ks
    }

    private fun clientCert(): X509Certificate = keyStore().getCertificate("atv") as X509Certificate

    private fun connect(host: String, port: Int): SSLSocket {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore(), pw)
        // TV:n har ett självsignerat certifikat. Själva TLS-kontrollen godkänner det, och i stället sparas
        // TV:ns nyckel första gången appen ansluter efter parkopplingen (se checkPin) och krävs sedan varje gång.
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sc = SSLContext.getInstance("TLS")
        sc.init(kmf.keyManagers, arrayOf<TrustManager>(trustAll), SecureRandom())
        val s = sc.socketFactory.createSocket() as SSLSocket
        try {
            s.connect(InetSocketAddress(host, port), 5000)
        } catch (e: IOException) {
            s.close()
            throw TvException("Får ingen kontakt med TV:n ($host). Är den påslagen och på samma wifi?")
        }
        s.soTimeout = 10_000
        s.startHandshake()
        return s
    }

    // ---------------- TV:ns certifikat (trust on first use) ----------------
    private val tvPrefs by lazy { ctx.getSharedPreferences("tv", Context.MODE_PRIVATE) }

    private fun fingerprint(s: SSLSocket): String {
        val cert = s.session.peerCertificates.firstOrNull() as? X509Certificate
            ?: throw TvException("TV:n visade inget certifikat.")
        return MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded).joinToString("") { "%02x".format(it) }
    }

    /** Första anslutningen efter parkopplingen sparar TV:ns nyckel; därefter måste den vara densamma. */
    private fun checkPin(host: String, s: SSLSocket) {
        val fp = fingerprint(s)
        val saved = tvPrefs.getString("pin|$host", null)
        if (saved == null) tvPrefs.edit().putString("pin|$host", fp).apply()
        else if (saved != fp) {
            s.close()
            throw TvException("TV:n känns inte igen (den har ett nytt certifikat). Parkoppla igen under ⚙︎.")
        }
    }

    private fun clearPins() {
        val e = tvPrefs.edit()
        for (k in tvPrefs.all.keys) if (k.startsWith("pin|")) e.remove(k)
        e.apply()
    }

    // ---------------- Parkoppling (port 6467) ----------------
    private var pairSocket: SSLSocket? = null

    private fun outer() = Pb().int(1, 2).int(2, 200)   // protocol_version = 2, status = OK

    private fun readOuter(input: InputStream): Map<Int, List<Any>> {
        val m = Pb.parse(receive(input))
        val status = m.long(2) ?: 200
        if (status != 200L) throw TvException(
            if (status == 402L) "Fel kod – försök igen." else "TV:n svarade med fel ($status)."
        )
        return m
    }

    /** Steg 1: be TV:n visa en kod. */
    fun startPairing(host: String) {
        pairSocket?.close()
        val s = connect(host, 6467)
        pairSocket = s
        val o = s.outputStream; val i = s.inputStream
        send(o, outer().msg(10, Pb().str(1, "atvremote").str(2, "Nytt att se")).toBytes())
        val hex = Pb().int(1, 3).int(2, 6)   // Encoding: HEXADECIMAL, 6 tecken
        while (true) {
            val m = readOuter(i)
            when {
                m.containsKey(11) -> send(o, outer().msg(20, Pb().msg(1, hex).int(3, 1)).toBytes())  // options
                m.containsKey(20) -> send(o, outer().msg(30, Pb().msg(1, hex).int(2, 1)).toBytes())  // configuration
                m.containsKey(31) -> { s.soTimeout = 120_000; return }                               // TV visar koden
                else -> throw TvException("Oväntat svar från TV:n under parkoppling.")
            }
        }
    }

    /** Steg 2: skicka koden som visas på TV:n. */
    fun finishPairing(codeIn: String) {
        val s = pairSocket ?: throw TvException("Starta parkopplingen först.")
        val code = codeIn.trim().uppercase()
        if (!Regex("^[0-9A-F]{6}$").matches(code)) throw TvException("Koden ska vara 6 tecken (0–9, A–F).")
        val client = clientCert().publicKey as RSAPublicKey
        val server = (s.session.peerCertificates[0] as X509Certificate).publicKey as RSAPublicKey
        fun BigInteger.unsigned(): ByteArray { val b = toByteArray(); return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(client.modulus.unsigned()); md.update(client.publicExponent.unsigned())
        md.update(server.modulus.unsigned()); md.update(server.publicExponent.unsigned())
        md.update(code.substring(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val hash = md.digest()
        if ((hash[0].toInt() and 0xFF) != code.substring(0, 2).toInt(16)) throw TvException("Fel kod – kontrollera och försök igen.")
        try {
            send(s.outputStream, outer().msg(40, Pb().bytes(1, hash)).toBytes())
            val m = readOuter(s.inputStream)
            if (!m.containsKey(41)) throw TvException("TV:n godkände inte parkopplingen.")
            clearPins()   // ny parkoppling: TV:ns nyckel sparas på nytt vid nästa anslutning
        } finally {
            s.close(); pairSocket = null
        }
    }

    // ---------------- Fjärrkontroll (port 6466) ----------------
    private val features = 1L or 2L or 32L or 64L or 512L   // PING, KEY, POWER, VOLUME, APP_LINK

    /** Öppnar en länk (t.ex. https://www.netflix.com/title/123) i rätt app på TV:n. */
    fun launch(host: String, appLink: String) {
        if (!ksFile.exists()) throw TvException("Parkoppla med TV:n först (under ⚙︎).")
        val s = try { connect(host, 6466) } catch (e: javax.net.ssl.SSLException) {
            throw TvException("TV:n godkänner inte appen. Parkoppla igen under ⚙︎.")
        }
        checkPin(host, s)
        s.use {
            val o = s.outputStream; val i = s.inputStream
            var active = features
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline) {
                val m = try { Pb.parse(receive(i)) } catch (e: javax.net.ssl.SSLException) {
                    throw TvException("TV:n godkänner inte appen. Parkoppla igen under ⚙︎.")
                }
                when {
                    m.containsKey(1) -> {   // remote_configure
                        val code1 = m.sub(1)?.long(1) ?: features
                        active = features and code1
                        if (active and 512L == 0L) throw TvException("TV:n stöder inte att öppna appar via länk.")
                        val info = Pb().int(3, 1).str(4, "1").str(5, "nyttattse").str(6, "1.0.0")
                        send(o, Pb().msg(1, Pb().int(1, active).msg(2, info)).toBytes())
                    }
                    m.containsKey(2) -> send(o, Pb().msg(2, Pb().int(1, active)).toBytes())          // set_active
                    m.containsKey(8) -> send(o, Pb().msg(9, Pb().int(1, m.sub(8)?.long(1) ?: 0)).toBytes())  // ping
                    m.containsKey(3) -> throw TvException("TV:n svarade med ett fel.")
                    m.containsKey(40) -> {  // remote_start: redo att ta emot kommandon
                        send(o, Pb().msg(90, Pb().str(1, appLink)).toBytes())
                        Thread.sleep(800)   // låt TV:n hinna läsa innan vi stänger
                        return
                    }
                }
            }
            throw TvException("TV:n svarade inte i tid.")
        }
    }

    // ---------------- Hitta TV:n på nätverket ----------------
    data class FoundTv(val name: String, val host: String)

    fun discover(timeoutMs: Long = 4000): List<FoundTv> {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("nyttattse").apply { setReferenceCounted(false); acquire() }
        val found = mutableListOf<FoundTv>()
        val queue = java.util.concurrent.LinkedBlockingQueue<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { queue.add(info) }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        try {
            nsd.discoverServices("_androidtvremote2._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val info = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val latch = CountDownLatch(1)
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) { latch.countDown() }
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val h = si.host?.hostAddress
                        if (h != null && found.none { it.host == h }) synchronized(found) { found.add(FoundTv(si.serviceName, h)) }
                        latch.countDown()
                    }
                })
                latch.await(3, TimeUnit.SECONDS)
            }
        } finally {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
            lock.release()
        }
        return found
    }
}

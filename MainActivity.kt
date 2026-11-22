package com.example.apkpackagerenamer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.security.auth.x500.X500Principal

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var selectedApkUri: Uri? = null

    private val apkFilePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedApkUri = uri
                arrayOf(uri)
            } ?: WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else {
            null
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            addJavascriptInterface(ApkBridge(), "AndroidBridge")

            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback

                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
                    }

                    return try {
                        apkFilePicker.launch(Intent.createChooser(intent, "APK ফাইল নির্বাচন করুন"))
                        true
                    } catch (e: Exception) {
                        this@MainActivity.filePathCallback?.onReceiveValue(null)
                        this@MainActivity.filePathCallback = null
                        false
                    }
                }
            }
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun getFileName(uri: Uri): String {
        var name = "input.apk"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex) ?: name
            }
        }
        return name
    }

    private fun postStatus(message: String) {
        val escaped = message
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        runOnUiThread {
            webView.evaluateJavascript("window.onNativeStatus(\"$escaped\");", null)
        }
    }

    private fun postSignedFileReady(filePath: String) {
        val escaped = filePath.replace("\\", "\\\\").replace("\"", "\\\"")
        runOnUiThread {
            webView.evaluateJavascript("window.onApkReady(\"$escaped\");", null)
        }
    }

    inner class ApkBridge {

        @JavascriptInterface
        fun getSelectedFileInfo(): String {
            val uri = selectedApkUri ?: return ""
            val name = getFileName(uri)
            val size = contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: -1L
            return "$name|$size"
        }

        @JavascriptInterface
        fun processAndSignApk(oldPackage: String, newPackage: String, alias: String, password: String) {
            Thread {
                try {
                    val uri = selectedApkUri
                    if (uri == null) {
                        postStatus("ত্রুটি: প্রথমে একটি APK ফাইল নির্বাচন করুন।")
                        return@Thread
                    }

                    postStatus("১/৪: APK ফাইল কপি ও বিশ্লেষণ করা হচ্ছে...")
                    val workDir = File(cacheDir, "apk_work").apply {
                        deleteRecursively()
                        mkdirs()
                    }
                    val inputApk = File(workDir, "input_base.apk")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(inputApk).use { output ->
                            input.copyTo(output)
                        }
                    } ?: run {
                        postStatus("ত্রুটি: নির্বাচিত ফাইলটি পড়া যাচ্ছে না।")
                        return@Thread
                    }

                    postStatus("২/৪: প্যাকেজ নাম ($oldPackage -> $newPackage) আপডেট করা হচ্ছে...")
                    val repackagedUnsigned = File(workDir, "repackaged_unsigned.apk")
                    repackageWithUpdatedManifest(inputApk, repackagedUnsigned, oldPackage, newPackage)

                    postStatus("৩/৪: KeyStore তৈরি ও APK সাইন করা হচ্ছে...")
                    val outDir = File(cacheDir, "signed").apply { mkdirs() }
                    val safePkg = newPackage.replace(".", "_")
                    val signedApk = File(outDir, "${safePkg}_signed.apk")
                    signApkV1(repackagedUnsigned, signedApk, alias, password)

                    postStatus("৪/৪: সম্পন্ন! সাইন করা ফাইল তৈরি হয়েছে:\n${signedApk.absolutePath}")
                    postSignedFileReady(signedApk.absolutePath)
                } catch (e: Exception) {
                    postStatus("প্রসেস ব্যর্থ হয়েছে: ${e.message ?: "অজানা ত্রুটি"}")
                }
            }.start()
        }

        @JavascriptInterface
        fun installApk(filePath: String) {
            runOnUiThread {
                try {
                    val file = File(filePath)
                    if (!file.exists()) {
                        postStatus("ত্রুটি: সাইন করা ফাইল পাওয়া যায়নি।")
                        return@runOnUiThread
                    }
                    val apkUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.provider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    postStatus("ইনস্টল শুরু করা যায়নি: ${e.message}")
                }
            }
        }
    }

    private fun repackageWithUpdatedManifest(
        sourceApk: File,
        targetApk: File,
        oldPackage: String,
        newPackage: String
    ) {
        val oldBytes = oldPackage.toByteArray(StandardCharsets.UTF_8)
        val newBytes = newPackage.toByteArray(StandardCharsets.UTF_8)

        ZipInputStream(BufferedInputStream(FileInputStream(sourceApk))).use { zis ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(targetApk))).use { zos ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val name = entry.name

                    if (name.startsWith("META-INF/") &&
                        (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC"))
                    ) {
                        entry = zis.nextEntry
                        continue
                    }

                    val rawBytes = readAllBytes(zis)
                    val finalBytes = if (name == "AndroidManifest.xml") {
                        replaceByteSequence(rawBytes, oldBytes, newBytes)
                    } else {
                        rawBytes
                    }

                    val newEntry = ZipEntry(name)
                    zos.putNextEntry(newEntry)
                    zos.write(finalBytes)
                    zos.closeEntry()

                    entry = zis.nextEntry
                }
            }
        }
    }

    private fun signApkV1(
        unsignedApk: File,
        signedApk: File,
        alias: String,
        password: String
    ) {
        val keyStore = KeyStore.getInstance("PKCS12")
        val ksFile = File(cacheDir, "signer_keystore.p12")
        val passChars = password.toCharArray()

        val privateKey: PrivateKey
        val cert: X509Certificate

        if (ksFile.exists()) {
            FileInputStream(ksFile).use { keyStore.load(it, passChars) }
        } else {
            keyStore.load(null, null)
        }

        if (keyStore.containsAlias(alias)) {
            privateKey = keyStore.getKey(alias, passChars) as PrivateKey
            cert = keyStore.getCertificate(alias) as X509Certificate
        } else {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val keyPair = kpg.generateKeyPair()

            val generatedCert = generateSelfSignedCertificate(keyPair, "CN=$alias, O=LocalSigner, C=BD")
            keyStore.setKeyEntry(alias, keyPair.private, passChars, arrayOf(generatedCert))
            FileOutputStream(ksFile).use { keyStore.store(it, passChars) }

            privateKey = keyPair.private
            cert = generatedCert
        }

        val manifestEntries = linkedMapOf<String, String>()
        val sfEntries = linkedMapOf<String, String>()

        ZipInputStream(BufferedInputStream(FileInputStream(unsignedApk))).use { zis ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(signedApk))).use { zos ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && !name.startsWith("META-INF/")) {
                        val content = readAllBytes(zis)
                        zos.putNextEntry(ZipEntry(name))
                        zos.write(content)
                        zos.closeEntry()

                        val digestBase64 = Base64.getEncoder().encodeToString(
                            java.security.MessageDigest.getInstance("SHA-256").digest(content)
                        )
                        manifestEntries[name] = digestBase64
                    }
                    entry = zis.nextEntry
                }

                val mfBuilder = StringBuilder().apply {
                    append("Manifest-Version: 1.0\r\n")
                    append("Created-By: Android ApkPackageRenamer\r\n\r\n")
                }

                for ((entryName, digest) in manifestEntries) {
                    val section = "Name: $entryName\r\nSHA-256-Digest: $digest\r\n\r\n"
                    mfBuilder.append(section)
                    val sectionDigest = Base64.getEncoder().encodeToString(
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest(section.toByteArray(StandardCharsets.UTF_8))
                    )
                    sfEntries[entryName] = sectionDigest
                }

                val mfBytes = mfBuilder.toString().toByteArray(StandardCharsets.UTF_8)
                val mfDigest = Base64.getEncoder().encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256").digest(mfBytes)
                )

                val sfBuilder = StringBuilder().apply {
                    append("Signature-Version: 1.0\r\n")
                    append("Created-By: Android ApkPackageRenamer\r\n")
                    append("SHA-256-Digest-Manifest: $mfDigest\r\n\r\n")
                }
                for ((entryName, sDigest) in sfEntries) {
                    sfBuilder.append("Name: $entryName\r\nSHA-256-Digest: $sDigest\r\n\r\n")
                }

                val sfBytes = sfBuilder.toString().toByteArray(StandardCharsets.UTF_8)

                val sig = Signature.getInstance("SHA256withRSA")
                sig.initSign(privateKey)
                sig.update(sfBytes)
                val sigBytes = sig.sign()

                zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                zos.write(mfBytes)
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("META-INF/CERT.SF"))
                zos.write(sfBytes)
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("META-INF/CERT.RSA"))
                zos.write(sigBytes)
                zos.closeEntry()
            }
        }
    }

    private fun generateSelfSignedCertificate(
        keyPair: java.security.KeyPair,
        dn: String
    ): X509Certificate {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val tempAlias = "temp_cert_${System.currentTimeMillis()}"
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            tempAlias,
            android.security.keystore.KeyProperties.PURPOSE_SIGN or android.security.keystore.KeyProperties.PURPOSE_VERIFY
        )
            .setCertificateSubject(X500Principal(dn))
            .setCertificateNotBefore(Date(System.currentTimeMillis() - 86400000L))
            .setCertificateNotAfter(Date(System.currentTimeMillis() + 10L * 365 * 86400000L))
            .build()

        val kpg = KeyPairGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_RSA,
            "AndroidKeyStore"
        )
        kpg.initialize(spec)
        kpg.generateKeyPair()
        val cert = ks.getCertificate(tempAlias) as X509Certificate
        ks.deleteEntry(tempAlias)
        return cert
    }

    private fun readAllBytes(zis: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var len = zis.read(buffer)
        while (len != -1) {
            out.write(buffer, 0, len)
            len = zis.read(buffer)
        }
        return out.toByteArray()
    }

    private fun replaceByteSequence(source: ByteArray, target: ByteArray, replacement: ByteArray): ByteArray {
        if (target.isEmpty() || source.size < target.size) return source
        val out = ByteArrayOutputStream()
        var i = 0
        while (i <= source.size - target.size) {
            var matched = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                out.write(replacement)
                i += target.size
            } else {
                out.write(source[i].toInt())
                i++
            }
        }
        while (i < source.size) {
            out.write(source[i].toInt())
            i++
        }
        return out.toByteArray()
    }
}

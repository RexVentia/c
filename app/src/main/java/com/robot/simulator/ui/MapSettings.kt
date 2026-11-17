package com.robot.simulator.ui

import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.amap.api.maps.MapsInitializer
import java.security.MessageDigest

object MapSettings {
    private fun preferences(context: Context) =
        context.getSharedPreferences("map-settings", Context.MODE_PRIVATE)

    fun apiKey(context: Context): String =
        preferences(context).getString("api-key", null)
            ?: runCatching {
                context.packageManager.getApplicationInfo(
                    context.packageName, PackageManager.GET_META_DATA,
                ).metaData?.getString("com.amap.api.v2.apikey").orEmpty()
            }.getOrDefault("")

    fun saveApiKey(context: Context, value: String) {
        preferences(context).edit().putString("api-key", value.trim()).apply()
    }

    fun initialize(context: Context): Boolean {
        val accepted = context.getSharedPreferences("amap-consent", Context.MODE_PRIVATE)
            .getBoolean("accepted", false)
        val key = apiKey(context)
        if (!accepted || key.isBlank()) return false
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        MapsInitializer.setApiKey(key)
        return true
    }

    fun certificateSha1(context: Context): String = runCatching {
        val signature = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_SIGNING_CERTIFICATES,
        ).signingInfo!!.apkContentsSigners.first()
        MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
            .joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }.getOrDefault("无法读取，请参考发布页的证书信息")
}

@Composable
fun MapKeyDialog(onDismiss: () -> Unit, onSaved: () -> Unit = onDismiss) {
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf(MapSettings.apiKey(context)) }
    val certificate = remember { MapSettings.certificateSha1(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("地图设置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("在高德开放平台创建 Android 平台 SDK Key，填写下方包名和 SHA1。Key 保存在本机，使用地图时由高德 SDK 用于鉴权。")
                SelectionContainer {
                    Text("包名：${context.packageName}\nSHA1：$certificate")
                }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("你的高德地图 Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text("首次填写后可创建路线。更换已使用的 Key 后，请完全退出应用并重新打开。")
            }
        },
        confirmButton = {
            TextButton(
                enabled = apiKey.isNotBlank(),
                onClick = {
                    MapSettings.saveApiKey(context, apiKey)
                    Toast.makeText(context, "已保存地图 Key", Toast.LENGTH_SHORT).show()
                    onSaved()
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

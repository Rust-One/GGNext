package com.littlecodesauce.ggnext.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.littlecodesauce.ggnext.ai.AiClient
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.ui.components.GgDivider
import com.littlecodesauce.ggnext.ui.components.GgInputBox
import com.littlecodesauce.ggnext.ui.components.GgSectionTitle
import com.littlecodesauce.ggnext.ui.components.GgSettingSwitchRow
import com.littlecodesauce.ggnext.ui.components.GgSurfaceCard
import com.littlecodesauce.ggnext.ui.components.GgTextField
import com.littlecodesauce.ggnext.ui.components.RoseButton
import com.littlecodesauce.ggnext.ui.components.clickableWithTooltip
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.theme.GgBorder
import com.littlecodesauce.ggnext.ui.theme.GgIconSize
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.GgSpacing
import com.littlecodesauce.ggnext.ui.theme.Rose
import kotlinx.coroutines.launch

/**
 * v0.9.0 新增：独立 AI 服务配置页。
 * 支持 OpenAI 兼容端点（http/https、域名或 IP:端口），模型可手填或从 /v1/models 自动拉取。
 * 配置写入 Session.ui.settings 的 ai_* 键，随 DataStore 持久化。
 */
@Composable
internal fun HomeAiSubPage(
    ui: Ui,
    onBack: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = lifecycleOwner.lifecycleScope
    val clipboard = LocalClipboardManager.current

    // ---- 持久化配置（缺省值）----
    val enabled = ui.settings["ai_enabled"] != "0"
    val autoExecute = ui.settings["ai_auto_execute"] != "0"
    val savedBaseUrl = ui.settings["ai_base_url"].orEmpty()
    val savedApiKey = ui.settings["ai_api_key"].orEmpty()
    val savedModelsCsv = ui.settings["ai_models"].orEmpty()
    val savedModel = ui.settings["ai_model"].orEmpty()

    var baseUrlTf by remember(savedBaseUrl) {
        mutableStateOf(TextFieldValue(savedBaseUrl, TextRange(savedBaseUrl.length)))
    }
    var apiKeyTf by remember(savedApiKey) {
        mutableStateOf(TextFieldValue(savedApiKey, TextRange(savedApiKey.length)))
    }
    var modelTf by remember(savedModel) {
        mutableStateOf(TextFieldValue(savedModel, TextRange(savedModel.length)))
    }

    var models by remember(savedModelsCsv) {
        mutableStateOf(savedModelsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() })
    }
    var isFetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }

    fun persist() {
        Session.setSetting("ai_base_url", baseUrlTf.text.trim())
        Session.setSetting("ai_api_key", apiKeyTf.text.trim())
        Session.setSetting("ai_model", modelTf.text.trim())
        Session.setSetting("ai_models", models.joinToString(","))
    }

    fun fetchModels() {
        if (isFetching) return
        fetchError = null
        isFetching = true
        scope.launch {
            val r = AiClient.fetchModels(baseUrlTf.text, apiKeyTf.text)
            isFetching = false
            if (r.ok && !r.data.isNullOrEmpty()) {
                models = r.data
                Session.setSetting("ai_models", r.data.joinToString(","))
                if (modelTf.text.isBlank() || r.data.contains(modelTf.text)) {
                    modelTf = modelTf.copy(r.data.first())
                    Session.setSetting("ai_model", r.data.first())
                }
                Session.toast(tr("已获取模型") + " ${r.data.size}")
            } else {
                fetchError = r.error
            }
        }
    }

    fun testConnection() {
        if (testing) return
        testResult = null
        testing = true
        scope.launch {
            val r = AiClient.chat(
                baseRaw = baseUrlTf.text,
                apiKey = apiKeyTf.text,
                model = modelTf.text,
                messages = listOf(AiClient.ChatMessage("user", "ping"))
            )
            testing = false
            testOk = r.ok
            testResult = if (r.ok) tr("连接成功") + "：${r.data?.take(80)}" else "${tr("连接失败")}：${r.error}"
        }
    }

    // 横屏时限宽并居中，避免设置项与输入框被拉到整屏宽度
    val aiConfigLandscape = androidx.compose.ui.platform.LocalConfiguration.current.let {
        it.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE || it.screenWidthDp > it.screenHeightDp
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Rose.Surface),
        contentAlignment = Alignment.TopCenter
    ) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .then(if (aiConfigLandscape) Modifier.widthIn(max = 900.dp) else Modifier.fillMaxWidth())
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = tr("返回"),
                    tint = Rose.Text,
                    modifier = Modifier.size(GgIconSize.Xl)
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = tr("AI 服务"),
                color = Rose.Text,
                fontSize = fsp(19f),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Rounded.SmartToy,
                contentDescription = null,
                tint = Rose.Primary,
                modifier = Modifier.size(GgIconSize.Lg)
            )
            Spacer(Modifier.width(8.dp))
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item("ai_enable_title") {
                GgSectionTitle(tr("服务开关"), fontSize = 13.5f, startPadding = GgSpacing.MdPlus)
            }
            item("ai_enable_card") {
                GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                    GgSettingSwitchRow(
                        title = tr("启用 AI 服务"),
                        subtitle = tr("配置 OpenAI 兼容接口，供脚本与智能体调用"),
                        checked = enabled,
                        onCheckedChange = { Session.setSetting("ai_enabled", if (it) "1" else "0") }
                    )
                }
            }

            item("ai_auto_execute_card") {
                GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                    GgSettingSwitchRow(
                        title = tr("全自动执行（默认同意全部）"),
                        subtitle = tr("开启后 AI 调用的所有工具自动执行，不再逐次弹窗确认；关闭则写操作需手动确认"),
                        checked = autoExecute,
                        onCheckedChange = { Session.setSetting("ai_auto_execute", if (it) "1" else "0") }
                    )
                }
            }

            item("ai_endpoint_title") {
                GgSectionTitle(tr("接口配置"), fontSize = 13.5f, startPadding = GgSpacing.MdPlus)
            }
            item("ai_endpoint_card") {
                GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                    Text(
                        text = tr("服务地址 (http/https · 域名或 IP:端口)"),
                        color = Rose.TextSecond,
                        fontSize = fsp(12.5f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GgInputBox(
                            modifier = Modifier.weight(1f),
                            height = 40.dp,
                            borderColor = Rose.Divider
                        ) {
                            GgTextField(
                                value = baseUrlTf,
                                onValueChange = { baseUrlTf = it },
                                modifier = Modifier.weight(1f),
                                placeholder = "https://api.openai.com 或 192.168.1.10:8080",
                                suppressSystemKeyboard = false
                            )
                        }
                    }
                    if (AiClient.isCleartext(baseUrlTf.text)) {
                        Text(
                            text = tr("当前为明文 HTTP，请确保网络可信（应用已允许明文流量）"),
                            color = Rose.Orange,
                            fontSize = fsp(11.5f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }

                    GgDivider()
                    Text(
                        text = tr("API Key（可选，按服务要求填写）"),
                        color = Rose.TextSecond,
                        fontSize = fsp(12.5f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GgInputBox(
                            modifier = Modifier.weight(1f),
                            height = 40.dp,
                            borderColor = Rose.Divider
                        ) {
                            GgTextField(
                                value = apiKeyTf,
                                onValueChange = { apiKeyTf = it },
                                modifier = Modifier.weight(1f),
                                placeholder = "sk-...",
                                suppressSystemKeyboard = false
                            )
                        }
                    }
                }
            }

            item("ai_model_title") {
                GgSectionTitle(tr("模型配置"), fontSize = 13.5f, startPadding = GgSpacing.MdPlus)
            }
            item("ai_model_card") {
                GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                    Text(
                        text = tr("模型名称（可手填，或从服务自动获取）"),
                        color = Rose.TextSecond,
                        fontSize = fsp(12.5f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GgInputBox(
                            modifier = Modifier.weight(1f),
                            height = 40.dp,
                            borderColor = Rose.Divider
                        ) {
                            GgTextField(
                                value = modelTf,
                                onValueChange = { modelTf = it },
                                modifier = Modifier.weight(1f),
                                placeholder = "gpt-4o-mini",
                                suppressSystemKeyboard = false
                            )
                        }
                        RoseButton(
                            label = if (isFetching) tr("获取中") else tr("自动获取"),
                            filled = true,
                            enabled = !isFetching && baseUrlTf.text.isNotBlank(),
                            modifier = Modifier.height(40.dp)
                        ) { fetchModels() }
                    }

                    if (fetchError != null) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = Rose.Red,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "${tr("获取失败")}：${fetchError}",
                                color = Rose.Red,
                                fontSize = fsp(11.5f)
                            )
                        }
                    }

                    if (models.isNotEmpty()) {
                        GgDivider()
                        Text(
                            text = tr("可用模型（点击选用）"),
                            color = Rose.TextSecond,
                            fontSize = fsp(12f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            models.forEach { m ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(GgShapes.Sm)
                                        .clickable {
                                            modelTf = modelTf.copy(m)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (m == modelTf.text) {
                                        Icon(
                                            Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = Rose.Green,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = m,
                                        color = if (m == modelTf.text) Rose.Primary else Rose.Text,
                                        fontSize = fsp(13f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item("ai_test_title") {
                GgSectionTitle(tr("连接测试"), fontSize = 13.5f, startPadding = GgSpacing.MdPlus)
            }
            item("ai_test_card") {
                GgSurfaceCard(shape = GgShapes.Xxl, borderWidth = GgBorder.Thin) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RoseButton(
                            label = if (testing) tr("测试中") else tr("发送测试请求"),
                            filled = true,
                            enabled = !testing && baseUrlTf.text.isNotBlank() && modelTf.text.isNotBlank(),
                            modifier = Modifier.height(40.dp)
                        ) { testConnection() }
                        RoseButton(
                            label = tr("保存配置"),
                            modifier = Modifier.height(40.dp)
                        ) {
                            persist()
                            Session.toast(tr("AI 配置已保存"))
                        }
                    }
                    if (testResult != null) {
                        GgDivider()
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                if (testOk) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = if (testOk) Rose.Green else Rose.Red,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = testResult ?: "",
                                color = if (testOk) Rose.Text else Rose.Red,
                                fontSize = fsp(12f),
                                modifier = Modifier.weight(1f)
                            )
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(GgShapes.Sm)
                                    .clickableWithTooltip(tooltip = tr("复制")) {
                                        clipboard.setText(AnnotatedString(testResult ?: ""))
                                        Session.toast(tr("已复制"))
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.ContentCopy,
                                    contentDescription = tr("复制"),
                                    tint = Rose.TextSecond,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }

            item("ai_note") {
                Text(
                    text = tr("说明：配置兼容 OpenAI Chat Completions 协议的服务（OpenAI、Ollama、vLLM、One API 等）。自动获取调用 /v1/models，测试请求调用 /v1/chat/completions。API Key 仅保存在本机。"),
                    color = Rose.TextSecond,
                    fontSize = fsp(11.5f),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
    }
}

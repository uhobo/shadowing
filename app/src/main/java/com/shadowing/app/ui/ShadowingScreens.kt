package com.shadowing.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadowing.app.car.ShadowingAppContainer
import com.shadowing.app.data.DocumentSummary
import com.shadowing.app.domain.model.EnginePhase
import com.shadowing.app.domain.model.ShadowingAction
import com.shadowing.app.domain.model.ShadowingSettings
import com.shadowing.app.domain.model.ShadowingUiState
import com.shadowing.app.tts.HighlightRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun ShadowingApp(
    onExecuteAction: (ShadowingAction) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val uiState by ShadowingAppContainer.engine.uiState.collectAsState()
    val settings by ShadowingAppContainer.settingsRepository.settingsFlow.collectAsState()
    val documents by ShadowingAppContainer.database.documentsFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var importFeedback by remember { mutableStateOf("") }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("🚗") },
                    label = { Text("Practice") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("📚") },
                    label = { Text("Library") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("⚙️") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> PracticeDrivingScreen(
                    uiState = uiState,
                    settings = settings,
                    onAction = onExecuteAction,
                    onSpeechRateChange = { newRate ->
                        scope.launch {
                            ShadowingAppContainer.settingsRepository.updateSettings {
                                it.copy(speechRate = newRate)
                            }
                        }
                    }
                )
                1 -> LibraryImportScreen(
                    documents = documents,
                    feedbackMessage = importFeedback,
                    onSelectDocument = { doc ->
                        scope.launch {
                            val sentences = ShadowingAppContainer.database.getSentencesForDocument(doc.id)
                            ShadowingAppContainer.engine.loadSession(
                                documentId = doc.id,
                                title = doc.title,
                                sentences = sentences,
                                startIndex = doc.currentSentenceIndex
                            )
                            selectedTab = 0
                        }
                    },
                    onImportPaste = { title, text ->
                        scope.launch {
                            val ingested = withContext(Dispatchers.Default) {
                                ShadowingAppContainer.ingestor.ingestRawText(title, text)
                            }
                            if (ingested.sentences.isEmpty()) {
                                importFeedback = "No speakable sentences found."
                            } else {
                                val id = ShadowingAppContainer.database.saveIngestedDocument(ingested)
                                ShadowingAppContainer.engine.loadSession(id, ingested.title, ingested.sentences, 0)
                                importFeedback = "Loaded ${ingested.sentences.size} sentences."
                                selectedTab = 0
                            }
                        }
                    },
                    onImportUrl = { url ->
                        scope.launch {
                            importFeedback = "Fetching article..."
                            runCatching {
                                ShadowingAppContainer.ingestor.ingestWebUrl(url)
                            }.onSuccess { ingested ->
                                if (ingested.sentences.isEmpty()) {
                                    importFeedback = "No speakable sentences extracted from URL."
                                } else {
                                    val id = ShadowingAppContainer.database.saveIngestedDocument(ingested)
                                    ShadowingAppContainer.engine.loadSession(id, ingested.title, ingested.sentences, 0)
                                    importFeedback = "Imported ${ingested.sentences.size} sentences."
                                    selectedTab = 0
                                }
                            }.onFailure { err ->
                                importFeedback = "URL error: ${err.message}"
                            }
                        }
                    },
                    onImportFileUri = { uri ->
                        scope.launch {
                            importFeedback = "Importing file..."
                            runCatching {
                                ShadowingAppContainer.ingestor.ingestUri(uri)
                            }.onSuccess { ingested ->
                                if (ingested.sentences.isEmpty()) {
                                    importFeedback = "No speakable sentences found in file."
                                } else {
                                    val id = ShadowingAppContainer.database.saveIngestedDocument(ingested)
                                    ShadowingAppContainer.engine.loadSession(id, ingested.title, ingested.sentences, 0)
                                    importFeedback = "Imported '${ingested.title}' (${ingested.sentences.size} sentences)."
                                    selectedTab = 0
                                }
                            }.onFailure { err ->
                                importFeedback = "File error: ${err.message}"
                            }
                        }
                    }
                )
                2 -> SettingsScreen(
                    settings = settings,
                    onUpdateSettings = { transform ->
                        scope.launch {
                            ShadowingAppContainer.settingsRepository.updateSettings(transform)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun PracticeDrivingScreen(
    uiState: ShadowingUiState,
    settings: ShadowingSettings,
    onAction: (ShadowingAction) -> Unit,
    onSpeechRateChange: (Float) -> Unit
) {
    val activeRange = if (uiState.activeWordStart != null && uiState.activeWordEnd != null) {
        HighlightRange(uiState.activeWordStart, uiState.activeWordEnd)
    } else {
        null
    }

    val annotatedSentence = remember(uiState.currentSentence, activeRange, uiState.failedWords) {
        SentenceHighlightFormatter.buildAnnotatedSentence(
            sentence = uiState.currentSentence,
            activeRange = activeRange,
            failedWords = uiState.failedWords
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val isAzureLive = !settings.useMockEvaluator && settings.azureApiKey.isNotBlank()
            val normalizedRegion = com.shadowing.app.evaluation.AzurePronunciationProtocol.normalizeRegion(settings.azureRegion)
            Text(
                text = if (isAzureLive) "🟢 Azure AI Speech Active ($normalizedRegion)" else "🟡 Offline Demo Mode (Enable Azure in Settings)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (isAzureLive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = uiState.documentTitle.ifBlank { "Driving Shadowing Coach" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Sentence ${uiState.currentSentenceIndex + 1} of ${maxOf(1, uiState.totalSentences)} • Goal: ${uiState.accuracyThreshold}%",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Status / Phase Banner
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (uiState.phase == EnginePhase.WORD_DRILL && !uiState.activeDrillWord.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Micro-Drill (0.65x speed): \"${uiState.activeDrillWord}\"",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    uiState.lastScore?.let { score ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Last Accuracy Score: $score% (Required: ${uiState.accuracyThreshold}%)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // Synchronized On-Screen Sentence Card (bolds the word currently spoken by TTS)
        Card(
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = annotatedSentence,
                    fontSize = 26.sp,
                    lineHeight = 36.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Quick Speech Rate Slider + Large Driving Transport Controls (Mirrors Steering-Wheel Buttons)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "TTS Speed: ${((settings.speechRate * 100).roundToInt() / 100f)}x",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = settings.speechRate,
                onValueChange = onSpeechRateChange,
                valueRange = 0.5f..1.5f
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = { onAction(ShadowingAction.RepeatOrPrevious) },
                    modifier = Modifier
                        .weight(1f)
                        .height(68.dp)
                ) {
                    Text("⏮ Repeat", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }

                val isPlaying = uiState.phase != EnginePhase.IDLE && uiState.phase != EnginePhase.PAUSED
                Button(
                    onClick = { onAction(ShadowingAction.TogglePause) },
                    modifier = Modifier
                        .weight(1.2f)
                        .height(68.dp)
                ) {
                    Text(
                        text = if (isPlaying) "⏸ Pause" else "▶ Start",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                FilledTonalButton(
                    onClick = { onAction(ShadowingAction.SkipNext) },
                    modifier = Modifier
                        .weight(1f)
                        .height(68.dp)
                ) {
                    Text("Skip ⏭", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun LibraryImportScreen(
    documents: List<DocumentSummary>,
    feedbackMessage: String,
    onSelectDocument: (DocumentSummary) -> Unit,
    onImportPaste: (title: String, text: String) -> Unit,
    onImportUrl: (url: String) -> Unit,
    onImportFileUri: (Uri) -> Unit
) {
    var pasteTitle by remember { mutableStateOf("") }
    var pasteContent by remember { mutableStateOf("") }
    var webUrl by remember { mutableStateOf("") }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) onImportFileUri(uri)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Import Practice Content (Phase 1)",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (feedbackMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = feedbackMessage,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        item {
            Button(
                onClick = {
                    fileLauncher.launch(
                        arrayOf(
                            "text/plain",
                            "application/epub+zip",
                            "application/pdf",
                            "*/*"
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("📂 Open Local Book / File (EPUB, PDF, TXT)")
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Import Public Web Article URL", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = webUrl,
                        onValueChange = { webUrl = it },
                        label = { Text("https://...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { if (webUrl.isNotBlank()) onImportUrl(webUrl.trim()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Fetch & Segment URL")
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Paste Text", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = pasteTitle,
                        onValueChange = { pasteTitle = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = pasteContent,
                        onValueChange = { pasteContent = it },
                        label = { Text("Paste English paragraph or article...") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (pasteContent.isNotBlank()) {
                                onImportPaste(pasteTitle, pasteContent)
                                pasteTitle = ""
                                pasteContent = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save & Start Pasted Text")
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Your Saved Documents",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        items(documents, key = { it.id }) { doc ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectDocument(doc) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(doc.title, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "${doc.sourceType} • Sentence ${doc.currentSentenceIndex + 1} / ${doc.totalSentences}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text("Resume ▶", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    settings: ShadowingSettings,
    onUpdateSettings: ((ShadowingSettings) -> ShadowingSettings) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Shadowing & Pronunciation Settings",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Required Accuracy Threshold: ${settings.accuracyThreshold}%",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "The app will not advance to the next sentence until your pronunciation score reaches this percentage.",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = settings.accuracyThreshold.toFloat(),
                    onValueChange = { v ->
                        onUpdateSettings { it.copy(accuracyThreshold = v.roundToInt()) }
                    },
                    valueRange = 50f..95f
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Normal TTS Playback Rate: ${((settings.speechRate * 100).roundToInt() / 100f)}x",
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = settings.speechRate,
                    onValueChange = { v -> onUpdateSettings { it.copy(speechRate = v) } },
                    valueRange = 0.5f..1.5f
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Consecutive Word Failures Before Micro-Drill: ${settings.drillFailThreshold}",
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = settings.drillFailThreshold.toFloat(),
                    onValueChange = { v -> onUpdateSettings { it.copy(drillFailThreshold = v.roundToInt()) } },
                    valueRange = 1f..5f,
                    steps = 3
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Word Micro-Drill Speed: ${((settings.drillSpeechRate * 100).roundToInt() / 100f)}x",
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = settings.drillSpeechRate,
                    onValueChange = { v -> onUpdateSettings { it.copy(drillSpeechRate = v) } },
                    valueRange = 0.45f..0.9f
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            val verifyScope = rememberCoroutineScope()
            var verifyStatus by remember { mutableStateOf("") }
            var isVerifying by remember { mutableStateOf(false) }

            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Azure AI Speech Pronunciation Assessment", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Offline Demo Scorer Mode (No API Key needed)")
                    Switch(
                        checked = settings.useMockEvaluator,
                        onCheckedChange = { enabled ->
                            onUpdateSettings { it.copy(useMockEvaluator = enabled) }
                        }
                    )
                }
                OutlinedTextField(
                    value = settings.azureApiKey,
                    onValueChange = { key ->
                        verifyStatus = ""
                        onUpdateSettings { it.copy(azureApiKey = key) }
                    },
                    label = { Text("Azure Speech Subscription Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = settings.azureRegion,
                    onValueChange = { reg ->
                        verifyStatus = ""
                        onUpdateSettings { it.copy(azureRegion = reg) }
                    },
                    label = { Text("Azure Region (e.g., eastus, westeurope)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        isVerifying = true
                        verifyStatus = "Testing connection to Azure..."
                        verifyScope.launch {
                            val result = ShadowingAppContainer.evaluator.verifyCredentials(
                                apiKey = settings.azureApiKey,
                                rawRegion = settings.azureRegion
                            )
                            isVerifying = false
                            result.onSuccess { msg ->
                                verifyStatus = "✅ $msg (Azure Live Scoring Enabled)"
                                onUpdateSettings {
                                    it.copy(
                                        useMockEvaluator = false,
                                        azureRegion = com.shadowing.app.evaluation.AzurePronunciationProtocol.normalizeRegion(settings.azureRegion)
                                    )
                                }
                            }.onFailure { err ->
                                verifyStatus = "❌ Connection failed: ${err.message}"
                            }
                        }
                    },
                    enabled = !isVerifying,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isVerifying) "Checking Azure Key..." else "🔌 Test Azure API Key & Activate")
                }
                if (verifyStatus.isNotBlank()) {
                    Text(
                        text = verifyStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (verifyStatus.startsWith("✅")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("USB Android Auto Setup Note", fontWeight = FontWeight.SemiBold)
                Text(
                    text = "For sideloaded debug builds, open Android Auto settings on your phone, tap Version 10 times to enable Developer Mode, open the top-right menu -> Developer settings, and enable 'Unknown sources'.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

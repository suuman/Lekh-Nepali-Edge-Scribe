package com.example

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit
) {
    val modelState by viewModel.modelState.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    
    val modelFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.loadModelFromUri(uri)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        // 1. Google Edge AI Model Settings Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Hub,
                                contentDescription = "Model icon",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "गुगल Edge AI मोडेल",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        // Action Badge / Status Label
                        Surface(
                            shape = CircleShape,
                            color = when (modelState) {
                                is MainViewModel.ModelState.Loaded -> MaterialTheme.colorScheme.primaryContainer
                                is MainViewModel.ModelState.Loading -> MaterialTheme.colorScheme.tertiaryContainer
                                is MainViewModel.ModelState.Error -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surface
                            }
                        ) {
                            Text(
                                text = when (modelState) {
                                    is MainViewModel.ModelState.Loaded -> "सक्रिय (Loaded)"
                                    is MainViewModel.ModelState.Loading -> "लोड हुँदैछ..."
                                    is MainViewModel.ModelState.Error -> "त्रुटि (Error)"
                                    else -> "लोड छैन (Unloaded)"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                color = when (modelState) {
                                    is MainViewModel.ModelState.Loaded -> MaterialTheme.colorScheme.onPrimaryContainer
                                    is MainViewModel.ModelState.Loading -> MaterialTheme.colorScheme.onTertiaryContainer
                                    is MainViewModel.ModelState.Error -> MaterialTheme.colorScheme.onErrorContainer
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                    when (val state = modelState) {
                        is MainViewModel.ModelState.Loaded -> {
                            Text(
                                text = "सक्रिय मोडेल (Active Model): ${state.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        is MainViewModel.ModelState.Loading -> {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "मोडेल लोड गरिँदैछ (Loading model)...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                                Text(
                                    text = "Edge runtime वातावरणमा मोडेल बाइनरीहरू सुरक्षित रूपमा सेट अप गरिँदैछ। यसले केही सेकेन्ड लिन सक्छ।",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                        }
                        is MainViewModel.ModelState.Error -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "मोडेल लोड गर्दा त्रुटि (Loading Error):",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = state.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.error.copy(alpha = 0.2f))
                                Text(
                                    text = "संभावित समाधान (Possible Fix):\n" +
                                            "१. यदि 'RET_CHECK (model_data.cc)' वा 'building tflite model' त्रुटि देखिन्छ भने, चयन गरिएको मोडेल फाइल बिग्रेको वा अधुरो डाउनलोड भएको छ।\n" +
                                            "२. हगिङ फेसबाट डाउनलोड गर्दा त्रुटि भएमा, या नेटवर्क समस्या भएमा, पुन: प्रयास गर्नुहोस् वा म्यानुअल रूपमा लोड गर्नुहोस्।\n" +
                                            "३. वा तल दिइएको टोकन बक्समा आफ्नो हगिङ फेस पहुँच टोकन (Hugging Face Read Token) राखेर पुन: प्रयास गर्नुहोस्।",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                        else -> {
                            Text(
                                text = "नेपाली एआई भाषण गतिविधिको लागि .litertlm, .literlm, .bin, वा .task मोडेल फाइल लोड गर्नुहोस् (जस्तै gemma-4-E2B-it.litertlm) ।",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = { modelFileLauncher.launch("*/*") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("load_model_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(imageVector = Icons.Default.FolderOpen, contentDescription = "Folder")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "मोडेल फाइल लोड गर्नुहोस् (Load .litertlm / .bin)")
                    }
                }
            }
        }

        // 1.5. Model Download & Original Sources Card
        item {
            var downloadUrlInput by remember { mutableStateOf("https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm") }
            var downloadFileNameInput by remember { mutableStateOf("gemma-4-E2B-it.litertlm") }
            var hfTokenInput by remember { mutableStateOf("") }
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = "Cloud Download",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "मोडेल डाउनलोड र मूल स्रोत (Downloads)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Text(
                        text = "नेपालscribe एआई प्रशोधनको लागि तपाईंले Google/Kaggle जस्ता आधिकारिक अथवा सामुदायिक स्रोतहरूबाट मोडेलहरू (.litertlm / .bin) डाउनलोड गर्न सक्नुहुन्छ।",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Visit Original Sources Section
                    Text(
                        text = "मूल आधिकारिक स्रोतहरू (Original Sources):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { uriHandler.openUri("https://www.kaggle.com/models/google/gemma-2") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Public, contentDescription = "Kaggle", modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Kaggle", fontSize = 11.sp, maxLines = 1)
                        }

                        Button(
                            onClick = { uriHandler.openUri("https://huggingface.co/models?search=gemma+litertlm") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Public, contentDescription = "HF", modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("HuggingFace", fontSize = 10.sp, maxLines = 1)
                        }

                        Button(
                            onClick = { uriHandler.openUri("https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android") },
                            modifier = Modifier.weight(1.2f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Icon(imageVector = Icons.Default.MenuBook, contentDescription = "Guide", modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("LLM Guide", fontSize = 10.sp, maxLines = 1)
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                    // Direct Built-in Downloader
                    Text(
                        text = "प्रत्यक्ष डाउनलोडर (Direct Downloader):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Choice chips
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("त्वरित लिङ्क (Quick Link):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilterChip(
                            selected = downloadUrlInput.contains("gemma-4-E2B-it"),
                            onClick = {
                                downloadUrlInput = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
                                downloadFileNameInput = "gemma-4-E2B-it.litertlm"
                            },
                            label = { Text("Gemma 4 E2B IT (सिफारिस गरिएको / Recommended)", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "Recommended",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }

                    OutlinedTextField(
                        value = downloadUrlInput,
                        onValueChange = { downloadUrlInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("मोडेल डाउनलोड URL (Direct File URL)", fontSize = 12.sp) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = downloadFileNameInput,
                        onValueChange = { downloadFileNameInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("बचत गरिने फाईलको नाम (Save as)", fontSize = 12.sp) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = hfTokenInput,
                        onValueChange = { hfTokenInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("हगिङ फेस टोकन (Hugging Face Read Token - Optional)", fontSize = 12.sp) },
                        placeholder = { Text("hf_...", fontSize = 12.sp) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        supportingText = {
                            Text(
                                "गतेकृत (Gated) मोडेलहरू डाउनलोड गर्न आवश्यक हगिङ फेस पहुँच रीड टोकन प्रविष्ट गर्नुहोस्।",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )

                    when (val state = downloadState) {
                        is MainViewModel.DownloadState.Downloading -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "डाउनलोड हुँदैछ: ${state.fileName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    IconButton(
                                        onClick = { viewModel.cancelDownload() },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Cancel",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                if (state.progress >= 0f) {
                                    LinearProgressIndicator(
                                        progress = state.progress,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    val progressPercentage = (state.progress * 100).toInt()
                                    val downloadedMb = state.bytesDownloaded / (1024f * 1024f)
                                    val totalMb = state.totalBytes / (1024f * 1024f)
                                    Text(
                                        text = String.format(Locale.US, "%d%% completed (%.1f MB / %.1f MB)", progressPercentage, downloadedMb, totalMb),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    val downloadedMb = state.bytesDownloaded / (1024f * 1024f)
                                    Text(
                                        text = String.format(Locale.US, "Downloading... (%.1f MB downloaded)", downloadedMb),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        is MainViewModel.DownloadState.Success -> {
                            Surface(
                                color = androidx.compose.ui.graphics.Color(0xFFE8F5E9),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(imageVector = Icons.Default.CheckCircle, contentDescription = "Success", tint = androidx.compose.ui.graphics.Color(0xFF2E7D32))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "मोडेल सफलतापूर्वक स्थानीय भण्डारणमा सुरक्षित भयो!",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = androidx.compose.ui.graphics.Color(0xFF1B5E20),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        is MainViewModel.DownloadState.Error -> {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(imageVector = Icons.Default.Error, contentDescription = "Error", tint = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "त्रुटि: ${state.error}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        else -> {
                            Button(
                                onClick = {
                                    if (downloadUrlInput.isNotBlank()) {
                                        viewModel.downloadModel(
                                            downloadUrlInput.trim(), 
                                            downloadFileNameInput.trim(),
                                            hfTokenInput.trim().takeIf { it.isNotEmpty() }
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .testTag("download_model_file_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                )
                            ) {
                                Icon(imageVector = Icons.Default.CloudDownload, contentDescription = "Download")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("मोडेल सिधै डाउनलोड गर्नुहोस् (Download)")
                            }
                        }
                    }
                }
            }
        }
    }
}

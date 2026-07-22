package com.example.midun.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.midun.ui.theme.*
import com.example.midun.viewmodel.AuthViewModel
import com.example.midun.viewmodel.DeviceViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InitScreen(
    onInitComplete: () -> Unit,
    authViewModel: AuthViewModel = hiltViewModel(),
    deviceViewModel: DeviceViewModel = hiltViewModel()
) {
    // 机型仅用于初始化页展示（设备标识类文案已按客户要求移除）。
    val phoneModel = remember { "${Build.MANUFACTURER} ${Build.MODEL}" }

    var step by remember { mutableIntStateOf(0) }
    // 0=检测设备 1=设置密码 2=绑定设备 3=初始化中 4=完成
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var bindDevice by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val initState by authViewModel.initState.collectAsState()

    LaunchedEffect(Unit) {
        delay(1500)
        step = 1
    }

    // M3.3: step 3 的推进改由真实 initDevice 结果驱动（原先是写死的 2s delay）。
    LaunchedEffect(initState) {
        when (val s = initState) {
            is AuthViewModel.InitState.Success -> step = 4
            is AuthViewModel.InitState.Error -> {
                errorMsg = s.message
                step = 1
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("初始化设置") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Primary,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 步骤指示器
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf("检测设备", "设置密码", "绑定设备", "完成").forEachIndexed { index, label ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(if (index <= step) Accent else TextSecondary.copy(0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (index < step) {
                                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            } else {
                                Text("${index + 1}", color = Color.White, fontSize = 14.sp)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(label, fontSize = 10.sp, color = if (index <= step) Primary else TextSecondary)
                    }
                    if (index < 3) {
                        Box(
                            modifier = Modifier
                                .padding(top = 15.dp)
                                .width(40.dp)
                                .height(2.dp)
                                .background(if (index < step) Accent else TextSecondary.copy(0.3f))
                        )
                    }
                }
            }

            Spacer(Modifier.height(40.dp))

            when (step) {
                0 -> {
                    Icon(Icons.Default.Usb, null, tint = Accent, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("正在检测设备...", fontSize = 16.sp)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(color = Accent)
                }
                1 -> {
                    Icon(Icons.Default.Lock, null, tint = Primary, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("设置安全密码", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("密码加密后存储在设备中", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.height(24.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("输入密码") },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    null
                                )
                            }
                        },
                        // 单行：否则键盘回车键会作为换行字符插入密码框（而非提交/收起）。Next 跳到确认框。
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("确认密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        // 单行 + Done：回车收起键盘，不再插入换行。
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.height(24.dp))

                    Button(
                        onClick = { step = 2 },
                        enabled = password.length >= 6 && password == confirmPassword,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text("生成根密钥")
                    }

                    if (password.isNotEmpty() && confirmPassword.isNotEmpty() && password != confirmPassword) {
                        Spacer(Modifier.height(8.dp))
                        Text("两次密码不一致", color = Danger, fontSize = 12.sp)
                    }

                    errorMsg?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = Danger, fontSize = 12.sp)
                    }
                }
                2 -> {
                    Icon(Icons.Default.PhoneAndroid, null, tint = Primary, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("设备绑定", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("绑定后设备仅限本手机使用", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.height(32.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("设备型号：", color = TextSecondary, fontSize = 14.sp)
                                Text(phoneModel, fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Switch(
                            checked = bindDevice,
                            onCheckedChange = { bindDevice = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = Accent)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("绑定当前设备", fontWeight = FontWeight.Medium)
                            Text(
                                if (bindDevice) "设备将仅限此手机使用，可后续解绑"
                                else "不绑定，任意手机均可使用此设备",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    Spacer(Modifier.height(32.dp))

                    Button(
                        onClick = { step = 3 },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text("确认并完成初始化")
                    }
                }
                3 -> {
                    // M3.3: 触发一次真实初始化；结果由顶部的 LaunchedEffect(initState) 推进到 step 4 或回退。
                    LaunchedEffect(Unit) {
                        authViewModel.initDevice(password, confirmPassword, bindDevice)
                    }
                    Spacer(Modifier.height(40.dp))
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(24.dp))
                    Text("正在初始化设备...", fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("生成安全密钥", fontSize = 12.sp, color = TextSecondary)
                }
                4 -> {
                    Spacer(Modifier.height(40.dp))
                    Icon(Icons.Default.CheckCircle, null, tint = Success, modifier = Modifier.size(80.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("初始化完成", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Success)
                    Spacer(Modifier.height(8.dp))
                    Text("设备已就绪", fontSize = 14.sp, color = TextSecondary)
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = onInitComplete,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text("前往登录")
                    }
                }
            }
        }
    }
}
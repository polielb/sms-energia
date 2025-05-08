package com.example.smsenergia

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.telephony.SmsManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.smsenergia.ui.theme.SmsEnergiaTheme
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// Importar para fecha y hora
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ContactoSMS(val nombre: String, val telefono: String)

class MainActivity : ComponentActivity() {

    // Lista de contactos para SMS (ahora vacía al inicio, se cargará desde SharedPreferences)
    private var contactosSMS = mutableStateListOf<ContactoSMS>()
    private lateinit var sharedPreferences: SharedPreferences

    // Mensajes por defecto
    private val DEFAULT_MENSAJE_DESCONEXION = "Corte Energia NOC 1 Edificio Tribunales - San Martin y Saavedra "
    private val DEFAULT_MENSAJE_CONEXION = "Energia restaurada NOC 1 Edificio Tribunales - San Martin y Saavedra "

    // Variables para los mensajes personalizados ....
    private var mensajeDesconexion by mutableStateOf("")
    private var mensajeConexion by mutableStateOf("")

    private var wasPlugged = false
    private var lastSentTimestamp = 0L
    private val MIN_SEND_INTERVAL = 10000L // 10 segundos mínimo entre envíos

    private val powerConnectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.action

            if (Intent.ACTION_BATTERY_CHANGED == status) {
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val isPlugged = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                        plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                        plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS

                val currentTime = System.currentTimeMillis()
                // Verificar que haya pasado suficiente tiempo desde el último envío
                if (currentTime - lastSentTimestamp < MIN_SEND_INTERVAL) {
                    return
                }

                // Verificar que la lista de contactos no esté vacía
                if (contactosSMS.isEmpty()) {
                    Toast.makeText(context, "No hay contactos para enviar SMS", Toast.LENGTH_SHORT).show()
                    return
                }

                if (isPlugged && !wasPlugged) {
                    // El dispositivo se ha conectado a la corriente
                    if (checkSmsPermission()) {
                        val mensaje = mensajeConexion + obtenerFechaHora()
                        sendSmsToAll(mensaje)
                        lastSentTimestamp = currentTime
                        Toast.makeText(context, "Conectado: SMS enviado", Toast.LENGTH_SHORT).show()
                    }
                    wasPlugged = true
                } else if (!isPlugged && wasPlugged) {
                    // El dispositivo se ha desconectado de la corriente
                    if (checkSmsPermission()) {
                        val mensaje = mensajeDesconexion + obtenerFechaHora()
                        sendSmsToAll(mensaje)
                        lastSentTimestamp = currentTime
                        Toast.makeText(context, "Desconectado: SMS enviado", Toast.LENGTH_SHORT).show()
                    }
                    wasPlugged = false
                }
            }
        }
    }

    // Función simple para obtener fecha y hora actual formateada
    private fun obtenerFechaHora(): String {
        val formato = SimpleDateFormat("dd/MM/yy HH:mm", Locale("es", "ES"))
        return formato.format(Date())
    }

    // Solicitar permisos con API moderna
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Permiso concedido para enviar SMS", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permiso denegado para enviar SMS", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicializar SharedPreferences
        sharedPreferences = getSharedPreferences("sms_contacts", MODE_PRIVATE)

        // Cargar la lista de contactos guardada
        cargarContactos()

        // Cargar los mensajes personalizados
        cargarMensajes()

        // Solicitar permiso para enviar SMS
        checkSmsPermission()

        // Registrar el BroadcastReceiver para detectar cambios en la alimentación
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(powerConnectionReceiver, filter)

        // Configurar la interfaz de usuario con Jetpack Compose
        setContent {
            SmsEnergiaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        contactos = contactosSMS,
                        mensajeDesconexion = mensajeDesconexion,
                        mensajeConexion = mensajeConexion,
                        onMensajeDesconexionChange = { nuevoMensaje ->
                            mensajeDesconexion = nuevoMensaje
                            guardarMensajes()
                        },
                        onMensajeConexionChange = { nuevoMensaje ->
                            mensajeConexion = nuevoMensaje
                            guardarMensajes()
                        },
                        onTestButtonClick = {
                            if (contactosSMS.isEmpty()) {
                                Toast.makeText(this, "No hay contactos para enviar SMS", Toast.LENGTH_SHORT).show()
                            } else if (checkSmsPermission()) {
                                val mensaje = "SMS prueba NOC 1 Edificio Tribunales - San Martin y Saavedra " + obtenerFechaHora()
                                sendSmsToAll(mensaje)
                                Toast.makeText(this, "SMS de prueba enviado", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onAddContact = { nombre, telefono ->
                            if (nombre.isNotBlank() && telefono.isNotBlank()) {
                                val nuevoContacto = ContactoSMS(nombre, telefono)
                                contactosSMS.add(nuevoContacto)
                                guardarContactos()
                                true
                            } else {
                                Toast.makeText(this, "Nombre y teléfono no pueden estar vacíos", Toast.LENGTH_SHORT).show()
                                false
                            }
                        },
                        onDeleteContact = { contacto ->
                            contactosSMS.remove(contacto)
                            guardarContactos()
                        }
                    )
                }
            }
        }
    }

    private fun cargarContactos() {
        val contactosJson = sharedPreferences.getString("contactos", null)
        if (contactosJson != null) {
            val tipo = object : TypeToken<List<ContactoSMS>>() {}.type
            val listaContactos = Gson().fromJson<List<ContactoSMS>>(contactosJson, tipo)
            contactosSMS.clear()
            contactosSMS.addAll(listaContactos)
        }
    }

    private fun cargarMensajes() {
        mensajeDesconexion = sharedPreferences.getString("mensaje_desconexion", DEFAULT_MENSAJE_DESCONEXION) ?: DEFAULT_MENSAJE_DESCONEXION
        mensajeConexion = sharedPreferences.getString("mensaje_conexion", DEFAULT_MENSAJE_CONEXION) ?: DEFAULT_MENSAJE_CONEXION
    }

    private fun guardarContactos() {
        val contactosJson = Gson().toJson(contactosSMS)
        sharedPreferences.edit().putString("contactos", contactosJson).apply()
    }

    private fun guardarMensajes() {
        sharedPreferences.edit()
            .putString("mensaje_desconexion", mensajeDesconexion)
            .putString("mensaje_conexion", mensajeConexion)
            .apply()
    }

    private fun checkSmsPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            // Usar el nuevo método de solicitud de permisos
            requestPermissionLauncher.launch(android.Manifest.permission.SEND_SMS)
            return false
        }
        return true
    }

    private fun sendSmsToAll(message: String) {
        try {
            // Verificar que la lista de contactos no esté vacía
            if (contactosSMS.isEmpty()) {
                Toast.makeText(this, "No hay contactos para enviar SMS", Toast.LENGTH_SHORT).show()
                return
            }

            // Crear una nueva instancia de SmsManager cada vez (evita reutilización)
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                this.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            // Enviar mensajes uno por uno con un pequeño retraso entre cada uno
            Thread {
                for (contacto in contactosSMS) {
                    try {
                        smsManager.sendTextMessage(contacto.telefono, null, message, null, null)
                        // Pequeña pausa entre envíos
                        Thread.sleep(500)
                    } catch (e: Exception) {
                        // Manejar errores específicos de cada número
                        runOnUiThread {
                            Toast.makeText(this, "Error al enviar SMS a ${contacto.nombre} (${contacto.telefono}): ${e.message}",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }.start()
        } catch (e: Exception) {
            Toast.makeText(this, "Error general al enviar SMS: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Desregistrar el BroadcastReceiver cuando la actividad se destruye
        unregisterReceiver(powerConnectionReceiver)
    }
}

@Composable
fun MainScreen(
    contactos: List<ContactoSMS>,
    mensajeDesconexion: String,
    mensajeConexion: String,
    onMensajeDesconexionChange: (String) -> Unit,
    onMensajeConexionChange: (String) -> Unit,
    onTestButtonClick: () -> Unit,
    onAddContact: (nombre: String, telefono: String) -> Boolean,
    onDeleteContact: (ContactoSMS) -> Unit
) {
    var nombre by remember { mutableStateOf("") }
    var telefono by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // Título y botón de prueba
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Detector de Carga",
                style = MaterialTheme.typography.headlineSmall
            )

            Button(onClick = onTestButtonClick) {
                Text("Enviar SMS de prueba")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Sección de mensajes personalizados
        MensajesPersonalizados(
            mensajeDesconexion = mensajeDesconexion,
            mensajeConexion = mensajeConexion,
            onMensajeDesconexionChange = onMensajeDesconexionChange,
            onMensajeConexionChange = onMensajeConexionChange
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Sección para agregar contactos
        Text(
            text = "Agregar Contacto",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Campo para nombre
        OutlinedTextField(
            value = nombre,
            onValueChange = { nombre = it },
            label = { Text("Nombre") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Campo para teléfono
        OutlinedTextField(
            value = telefono,
            onValueChange = { telefono = it },
            label = { Text("Teléfono") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Botón para agregar contacto
        Button(
            onClick = {
                val success = onAddContact(nombre, telefono)
                if (success) {
                    nombre = ""
                    telefono = ""
                }
            },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Agregar Contacto")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Lista de contactos
        Text(
            text = "Lista de Contactos",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (contactos.isEmpty()) {
            Text(
                text = "No hay contactos registrados",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f)
            ) {
                items(contactos) { contacto ->
                    ContactoItem(
                        contacto = contacto,
                        onDelete = { onDeleteContact(contacto) }
                    )
                }
            }
        }
    }
}

@Composable
fun MensajesPersonalizados(
    mensajeDesconexion: String,
    mensajeConexion: String,
    onMensajeDesconexionChange: (String) -> Unit,
    onMensajeConexionChange: (String) -> Unit
) {
    var expandidoDesconexion by remember { mutableStateOf(false) }
    var expandidoConexion by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            Text(
                text = "Personalización de mensajes",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Mensaje de desconexión
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandidoDesconexion = !expandidoDesconexion },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Mensaje desconexión",
                    style = MaterialTheme.typography.bodyLarge
                )

                Icon(
                    imageVector = if (expandidoDesconexion) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expandidoDesconexion) "Contraer" else "Expandir"
                )
            }

            if (expandidoDesconexion) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = mensajeDesconexion,
                    onValueChange = onMensajeDesconexionChange,
                    label = { Text("Mensaje cuando se corta la energía") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Mensaje de conexión
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandidoConexion = !expandidoConexion },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Mensaje conexión",
                    style = MaterialTheme.typography.bodyLarge
                )

                Icon(
                    imageVector = if (expandidoConexion) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expandidoConexion) "Contraer" else "Expandir"
                )
            }

            if (expandidoConexion) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = mensajeConexion,
                    onValueChange = onMensajeConexionChange,
                    label = { Text("Mensaje cuando se restaura la energía") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun ContactoItem(
    contacto: ContactoSMS,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = contacto.nombre,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = contacto.telefono,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Eliminar contacto",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
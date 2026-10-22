package se.healthbridge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.healthbridge.app.ui.theme.HealthBridgeTheme

/** Privacy policy shown from Health Connect's permission screen. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HealthBridgeTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Integritet i HealthBridge", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "HealthBridge hämtar dina mätvärden (vikt, kroppssammansättning, blodtryck, puls, " +
                                "sömn och aktivitet) från ditt Withings-konto och skriver in dem i Health Connect " +
                                "på den här telefonen, så att Samsung Health och andra appar kan se dem.",
                        )
                        Text(
                            "Appen läser data från Health Connect (t.ex. från Samsung Health) enbart för att visa " +
                                "översikten och för export till en CSV-fil som du själv väljer att spara.",
                        )
                        Text(
                            "Ingen hälsodata skickas till någon annan server än Withings egna API. " +
                                "Inloggningsnycklar lagras krypterat på telefonen och kan tas bort när som helst " +
                                "via \"Koppla från Withings\".",
                        )
                        Button(onClick = { finish() }) { Text("Stäng") }
                    }
                }
            }
        }
    }
}

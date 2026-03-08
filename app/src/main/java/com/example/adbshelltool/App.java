package com.example.adbshelltool;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import org.conscrypt.Conscrypt;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.security.Security;

import io.github.muntashirakon.adb.PRNGFixes;

/**
 * App — clase Application personalizada. Se ejecuta antes que cualquier Activity.
 *
 * Hace dos cosas criticas al arrancar:
 *
 * 1. HiddenApiBypass (attachBaseContext):
 *    Desde Android 9 (API 28), las APIs internas como android.sun.security.x509.*
 *    estan en la "hidden API list" y Android bloquea su uso en apps de terceros.
 *    HiddenApiBypass anula esa restriccion antes de que se cargue cualquier clase.
 *    Debe hacerse en attachBaseContext, que es lo primero que ejecuta el proceso.
 *
 * 2. PRNGFixes + Conscrypt (onCreate):
 *    - PRNGFixes: corrige un bug historico del generador de numeros aleatorios
 *      en versiones antiguas de Android (ya no critico en API 30+ pero no hace dano).
 *    - Conscrypt: registra el proveedor TLS como prioritario para que todas las
 *      conexiones SSL/TLS usen TLSv1.3, requerido por el protocolo de pairing ADB.
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // Correcciones del generador de numeros aleatorios (buena practica)
        PRNGFixes.apply();

        // Registrar Conscrypt como proveedor TLS prioritario.
        // insertProviderAt(..., 1) lo pone en primera posicion, antes del proveedor
        // por defecto de Android, para que todas las operaciones TLS lo usen.
        Security.insertProviderAt(Conscrypt.newProvider(), 1);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);

        // Desbloquear las hidden APIs de Android (android.sun.*, etc.)
        // Solo necesario en Android 9+ (API 28+). "L" como prefijo desbloquea
        // todas las clases cuyo descriptor empieza por L (es decir, todas).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L");
        }
    }
}

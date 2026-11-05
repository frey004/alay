# Alay — asistente de voz con avatar

Un asistente tipo "Hey Google" / Siri. En vez de una barra, cuando lo activas aparece un **avatar animado**: mueve la boca al hablar, parpadea, "piensa" y brilla mientras te escucha.


## 📱 App nativa de Android (carpeta `android/`)

La versión de Android funciona **como Siri o Hey Google de verdad**: te escucha aunque estés en otra app o la pantalla esté bloqueada, y al decir **"Oye Alay"** aparece el avatar encima de todo.

### Instalarla
1. Consigue **alay.apk**: GitHub Actions lo compila cada vez que cambia el código (pestaña *Actions* → "APK de Android" → la última ejecución → *Artifacts* → `alay-apk`, que es un .zip con el APK dentro).
2. Ábrelo. Android te pedirá permitir "instalar apps de origen desconocido" para tu navegador: acéptalo.
3. Abre Alay y sigue los pasos de la pantalla:
   1. Permiso de micrófono y notificaciones
   2. Permitir que aparezca sobre otras apps (para que el avatar salga aunque estés en otra app)
   3. Descargar el modelo de voz (40 MB, una sola vez; después la palabra clave se detecta sin internet)
   4. Que Android no la cierre para ahorrar batería
4. Activa **"Escuchar la palabra de activación siempre"**. Aparecerá una notificación fija "Alay está escuchando".
5. Opcional: **"Poner Alay como asistente del teléfono"**. Así también se abre al mantener pulsado el botón de inicio o de encendido, o al deslizar desde la esquina, en lugar de Google.

### Cómo funciona
- **Palabra de activación**: un servicio en primer plano usa [Vosk](https://alphacephei.com/vosk/) (reconocimiento de voz sin conexión, en el propio teléfono) para oír "Oye Alay".
- **Avatar**: una pantalla transparente encima de todo, también sobre la pantalla bloqueada. El robot se dibuja y anima en tiempo real: flota, parpadea, brilla al escuchar, mueve la cabeza al pensar y mueve la boca con cada palabra que dice.
- **Tu orden** se entiende con el reconocimiento de voz de Google y la respuesta se dice con la voz del sistema (Ajustes → Texto a voz para cambiarla).
- **Comandos del teléfono**: hora y fecha, alarmas ("pon una alarma a las 7 y media"), temporizadores, linterna, abrir cualquier app instalada ("abre WhatsApp"), poner música en YouTube, buscar en Google, cuentas y chistes.
- **Clave de API de Anthropic (opcional)**: con ella responde a cualquier pregunta y recuerda la conversación.

### Consejos
- "Alay" no es una palabra del diccionario, así que el reconocedor a veces la oye como "a la" o "allí". La app ya acepta esas variantes. Si aun así no te responde, en Ajustes pon otra palabra de activación con palabras normales, por ejemplo `oye robot` o `hola asistente`.
- Escuchar todo el tiempo gasta algo de batería.
- Después de reiniciar el teléfono, abre Alay una vez para que vuelva a escuchar (Android no deja usar el micrófono en segundo plano sin abrir la app).

### Compilarla tú
Con Android Studio, abre la carpeta `android/` y pulsa ▶. O desde la terminal: `cd android && ./gradlew assembleRelease`.

---

## 🌐 Versión web (PWA)

### Cómo se usa
1. Abre la app en **Chrome o Edge** (Android o PC). Safari reciente también funciona, con limitaciones.
2. Pulsa **🎤 Activar micrófono** y da permiso.
3. Di **"Oye Alay"**. Aparece el avatar. Luego dile lo que quieras, o dilo todo junto: *"Oye Alay, ¿qué hora es?"*
4. Cuando responde, sigue escuchando unos segundos por si quieres preguntar algo más. Di *"adiós"* o pulsa ✕ para cerrarlo.

### Comandos que entiende sin internet ni clave
- ¿Qué hora es? / ¿Qué día es hoy?
- Cuéntame un chiste
- Busca *recetas de pasta*
- Pon *música de Bad Bunny* (busca en YouTube)
- Abre YouTube / Google / WhatsApp / Maps / Instagram / Gmail
- Pon un temporizador de 5 minutos
- ¿Cuánto es 12 por 8?

### Respuestas inteligentes (opcional)
En **⚙️ Ajustes** puedes pegar una clave de API de Anthropic (console.anthropic.com). Con ella Alay responde a cualquier pregunta y recuerda la conversación. La clave se guarda solo en tu dispositivo.

En Ajustes también puedes cambiar la palabra de activación (puedes poner varias separadas por comas), el nombre y la voz.

### Cómo abrirla en el móvil
El micrófono solo funciona con **HTTPS** (o `localhost`). La forma más fácil es usar GitHub Pages:
Settings → Pages → Branch: esta rama → `/ (root)`. Después abre la URL en Chrome del móvil y elige **"Añadir a pantalla de inicio"**. Así queda instalada como una app.

Para probarla en el ordenador: `python3 -m http.server` y abre http://localhost:8000

### Limitaciones de la versión web
- Solo escucha la palabra clave **mientras la página está abierta y en pantalla**. Para que funcione siempre, usa la app de Android.
- El reconocimiento de voz de Chrome usa los servidores de Google, así que necesita internet.

### Archivos
- `index.html`: pantalla principal, avatar SVG y ajustes
- `style.css`: estilos y animaciones del avatar
- `app.js`: palabra de activación, reconocimiento de voz, comandos, IA, síntesis de voz y lip-sync
- `manifest.json`, `sw.js`, `icon.svg`: hacen que se pueda instalar como app (PWA)

# Alay — asistente de voz con avatar

Un asistente tipo "Hey Google" / Siri. En vez de una barra, cuando lo activas aparece un **avatar animado**: mueve la boca al hablar, parpadea, "piensa" y brilla mientras te escucha.

## Cómo se usa
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

## Cómo abrirla en el móvil
El micrófono solo funciona con **HTTPS** (o `localhost`). La forma más fácil es usar GitHub Pages:
Settings → Pages → Branch: esta rama → `/ (root)`. Después abre la URL en Chrome del móvil y elige **"Añadir a pantalla de inicio"**. Así queda instalada como una app.

Para probarla en el ordenador: `python3 -m http.server` y abre http://localhost:8000

## Limitaciones
- Es una app web: solo escucha la palabra clave **mientras la app está abierta y en pantalla**. La pantalla se mantiene encendida mientras escucha. Si quieres que funcione con la pantalla apagada o desde cualquier app, como Siri, hace falta una app nativa de Android que se registre como asistente del sistema.
- El reconocimiento de voz de Chrome usa los servidores de Google, así que necesita internet.

## Archivos
- `index.html`: pantalla principal, avatar SVG y ajustes
- `style.css`: estilos y animaciones del avatar
- `app.js`: palabra de activación, reconocimiento de voz, comandos, IA, síntesis de voz y lip-sync
- `manifest.json`, `sw.js`, `icon.svg`: hacen que se pueda instalar como app (PWA)

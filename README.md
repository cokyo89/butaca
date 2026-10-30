# Butaca

Reproductor de torrents para Android con biblioteca estilo Jellyfin.

- Reproduce enlaces magnet y archivos .torrent mientras se descargan (descarga en orden).
- Todo lo que abres entra en **Mi biblioteca** con carátula, año y sinopsis en español (TMDB).
- **Seguir viendo**: recuerda por dónde ibas.
- **Favoritos**: mantén pulsada una carátula.
- **Cine clásico gratis**: catálogo de dominio público de Internet Archive (con filtro «En español»).
- Si un vídeo no tiene sonido o no se reproduce (AC3, DTS…), usa «Abrir en otro reproductor» con VLC.
- Los enlaces magnet que pulses en Chrome se pueden abrir directamente con Butaca.

## Cómo conseguir el APK (sin instalar nada en el ordenador)

1. En github.com crea un repositorio nuevo, por ejemplo `butaca`.
2. Pulsa **Add file → Upload files** y arrastra **el contenido** de esta carpeta
   (incluida la carpeta oculta `.github`). Pulsa **Commit changes**.
   - Si la carpeta `.github` no se sube al arrastrar, crea el archivo a mano:
     **Add file → Create new file**, nombre `.github/workflows/build.yml`, y pega su contenido.
3. Ve a la pestaña **Actions**. Verás «Compilar APK» en marcha (tarda 5-10 minutos).
4. Cuando salga en verde, entra en la ejecución y abajo, en **Artifacts**, descarga `Butaca-apk`.
5. Descomprime el zip, pasa `app-debug.apk` al móvil e instálalo
   (permite «instalar apps de origen desconocido» si te lo pide).

Si sale en rojo, abre el paso que ha fallado y copia el error.

## Carátulas y sinopsis

En **Ajustes** pega una clave API (v3) gratuita de themoviedb.org.
Sin clave funciona igual, pero la biblioteca muestra el nombre del archivo.

## Requisitos

Android 9 o superior.

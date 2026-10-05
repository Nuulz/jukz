# Guía para subir jukz a Modrinth y CurseForge

Los textos que se pegan en las tiendas están en inglés a propósito (así los entiende todo el mundo y los
moderadores). Están en [`LISTING.md`](LISTING.md). Esta guía solo te dice **qué hacer y dónde pegar cada cosa**.

Todo lo que necesitas está en esta carpeta (`docs/store/`): el icono (`icon.png`) y las capturas.
El jar está en https://github.com/Nuulz/jukz/releases/latest (`jukz-0.2.1.jar`, descárgalo).

## Datos que te van a pedir (copia tal cual)

| Te piden | Pon |
|---|---|
| Nombre | jukz |
| Resumen corto | el de `LISTING.md` (sección Basics, "Summary") |
| Tipo de proyecto | Mod |
| Loader / cargador | Fabric |
| Versión de Minecraft | 1.21.1 |
| Cliente / servidor | Cliente: requerido. Servidor: no hace falta |
| Categorías | Multijugador, Social, Cosméticos, Utilidades (las que existan con esos nombres) |
| Licencia | MIT |
| Código fuente | https://github.com/Nuulz/jukz |
| Reportar errores | https://github.com/Nuulz/jukz/issues |
| Sitio web | https://nuulm.com/jukz |
| Donaciones | https://ko-fi.com/nobmz |
| Descripción larga | el bloque de la sección "Description" de `LISTING.md` |
| Dependencias obligatorias | Fabric API, Fabric Language Kotlin, owo-lib |

## Modrinth (más rápido, suele aprobar en 1 o 2 días)

1. Entra a https://modrinth.com e inicia sesión con tu GitHub.
2. Arriba a la derecha: tu avatar → **Dashboard** → **Projects** → **Create a project**.
3. Pon el nombre "jukz", el resumen y elige "Mod". Se crea el borrador.
4. En la página del proyecto, pestaña **Description**: pega la descripción larga.
5. **Settings → General**: sube `icon.png`. En **Links** pon código fuente, errores, sitio web y Ko-fi.
6. **Settings → Tags**: categorías. **Settings → License**: MIT.
7. **Gallery**: sube las capturas en el orden de `LISTING.md` (la primera es la portada).
8. **Versions → Create a version**: sube `jukz-0.2.1.jar`, número `0.2.1`, loader Fabric, versión 1.21.1.
   En **Dependencies** agrega Fabric API, Fabric Language Kotlin y owo-lib como *Required*.
   Copia las notas del `CHANGELOG.md` (sección 0.2.1).
9. Vuelve al proyecto y pulsa **Submit for review**. Solo queda esperar.

## CurseForge (más lento, la revisión puede tardar varios días)

1. Entra a https://console.curseforge.com e inicia sesión (puede pedirte crear la cuenta de autor).
2. **Create project** → juego **Minecraft** → tipo **Mods**.
3. Pon nombre, resumen, categorías, licencia MIT, enlaces (código, errores, web) y Ko-fi como donación.
4. **Description**: pega la descripción larga. **Images**: sube el icono y las capturas.
5. **Files → Upload file**: sube `jukz-0.2.1.jar`, marca Fabric, Minecraft 1.21.1 y Java 21.
   En **Relations** agrega como *Required* Fabric API, Fabric Language Kotlin y owo-lib.
6. Envía a revisión (**Submit for review**) y espera.

## Después

- Si te piden cambios, dímelo y los hago (casi siempre es la descripción o una aclaración).
- Cuando aprueben, pásame el ID de cada proyecto y un token de API como secreto de GitHub, y hago que
  cada release nueva suba el jar a las dos tiendas sola.

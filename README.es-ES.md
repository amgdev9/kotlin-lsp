

# Servidor de Lenguaje Kotlin

[![Status](https://github.com/amgdev9/kotlin-lsp/actions/workflows/push.yml/badge.svg)](https://github.com/amgdev9/kotlin-lsp/actions/workflows/push.yml)
[![Chat](https://img.shields.io/badge/chat-on%20discord-7289da)](https://discord.gg/mSYevKDnA5)

Esta es una implementación del [Language Server Protocol](https://microsoft.github.io/language-server-protocol/specification) para el lenguaje de programación [Kotlin](https://kotlinlang.org), que aprovecha la [Analysis API](https://github.com/JetBrains/kotlin/blob/master/docs/analysis/analysis-api/analysis-api.md) de Kotlin para proporcionar diagnósticos en tiempo real, así como análisis de sintaxis y semántica de archivos y bibliotecas fuente de Kotlin.

## Estado actual

Actualmente, este servidor de lenguaje está en sus primeras etapas y, por lo tanto, no está listo para su uso en entornos de producción (aún). Por el momento, este servidor se está preparando para analizar su propia base de código, con soporte próximo para otros proyectos. Estos son los pasos más importantes que debemos dar para mejorar su usabilidad:

- Integración con sistemas de compilación: actualmente contamos con 2 integraciones disponibles:
    - Gradle
    - Basado en archivos: para otros sistemas de compilación, puedes escribir un archivo `.kotlinlsp-modules.json` en la raíz de tu proyecto con los módulos y dependencias que contiene. Tienes un ejemplo en `org.kotlinlsp.setup.Scenario.Kt`

- Solución de indexación: para proporcionar características como autocompletado y búsqueda de referencias, así como caché para mejorar el rendimiento del análisis, necesitamos crear un índice donde almacenemos todas las referencias utilizadas en el proyecto. Para esto, estamos utilizando múltiples almacenes clave-valor con [RocksDB](https://rocksdb.org) en disco y realizamos una indexación en segundo plano de todo el proyecto, actualizándola de forma incremental a medida que el usuario modifica los archivos fuente. Uno de los objetivos de este LS es ofrecer un tiempo de inicio rápido, por lo que los diagnósticos se reportan lo más rápidamente posible.

### Características implementadas
- ✅ Diagnósticos en tiempo real: funcionando para esta base de código
- ✅ Información al pasar el cursor (Hover): completamente funcional
- 🚧 Ir a la definición: funcionando (con soporte de descompilación), necesita ajustes al resolver bibliotecas de Kotlin y clases del JDK
- 🚧 Integración con sistemas de compilación: existe soporte para
    * Proyectos Gradle (de un solo módulo y multi-módulo) 
    * Proyectos Android de un solo módulo (utiliza la variante de depuración y aún no maneja la fusión de conjuntos de código fuente)
    * Requiere trabajo en:
        * Proyectos Android multi-módulo
        * Proyectos KMP (orientados a JVM, el objetivo nativo requiere investigación sobre cómo implementarlo)

## Instalación
Proporcionamos un archivo zip de distribución, que puedes descargar desde [GitHub Releases](https://github.com/amgdev9/kotlin-lsp/releases/latest). Alternativamente, existen métodos no oficiales para instalarlo, proporcionados por la comunidad:
- Nix: https://tangled.sh/@weethet.bsky.social/nix-packages, accesible mediante `packages.${system}.kotlin-lsp` o en un overlay

## Compilación y ejecución

Para compilar el servidor de lenguaje, simplemente ejecuta el script `./scripts/build.sh` en el directorio raíz, el cual compila el proyecto usando gradle, lo empaqueta como un zip de distribución y lo descomprime en la carpeta `./lsp-dist`. Una vez compilado, necesitas integrarlo en un editor de código para probar su funcionalidad. Por ejemplo, en neovim se puede usar la siguiente configuración:

```lua
local root_dir = vim.fs.root(0, {"settings.gradle.kts", "settings.gradle"})
if not root_dir then
    root_dir = vim.fs.root(0, {"build.gradle.kts", "build.gradle"})
end
local lsp_folder = "... path to lsp-dist folder ..."
vim.lsp.config['kotlinlsp'] = {
    cmd = { '' .. lsp_folder .. '/kotlin-lsp-0.1a/bin/kotlin-lsp' },
    filetypes = { 'kotlin' },
    root_dir = root_dir
}
vim.lsp.enable('kotlinlsp')
```

Después de eso, abre el editor de código en un archivo Kotlin de este proyecto y deberías ver que se reportan diagnósticos. En el archivo `Log.kt` puedes configurar la verbosidad de los registros (logs).

## Ejecución de pruebas

Para ejecutar las pruebas, simplemente ejecuta el comando `./gradlew test`. Las pruebas se realizan en torno a la interfaz LSP, por lo que probamos contra interacciones reales de usuarios, lo que proporciona una buena red de seguridad en caso de refactorización o actualización de dependencias.

## Contribuciones

¡Las contribuciones son bienvenidas! Intento mejorar este servidor de lenguaje en mi tiempo libre, pero el progreso será lento si lo hago solo, por lo que mientras más colaboradores tenga este proyecto, más rápido será el desarrollo. No dudes en contactarme si quieres contribuir, tienes alguna duda sobre cómo empezar o necesitas más contexto sobre la API de Análisis (Analysis API) (no soy un experto, pero puedo proporcionar mis propias investigaciones para ayudar al desarrollo del proyecto).

No dudes en crear issues y enviar pull requests, los responderé lo antes posible.

## Recursos

Para ayudar en el desarrollo de este proyecto, estos recursos son extremadamente valiosos:
- [Kotlin Analysis API](https://github.com/JetBrains/kotlin/tree/master/analysis): especialmente la plataforma independiente (standalone), que es una implementación estática de solo lectura que podemos usar como referencia base
- [IntelliJ IDEA Kotlin plugin](https://github.com/JetBrains/intellij-community/tree/master/plugins/kotlin): el plugin de Kotlin implementa la API de Análisis, así como la interfaz de plataforma, por lo que lo tenemos como base

## Patrocinar este proyecto

Si deseas apoyar económicamente este proyecto, acepto donaciones a través de 

[!["Buy Me A Coffee"](https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png)](https://www.buymeacoffee.com/amgdev9)

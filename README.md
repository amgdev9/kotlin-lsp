# Kotlin Language Server

[![Status](https://github.com/amgdev9/kotlin-lsp/actions/workflows/push.yml/badge.svg)](https://github.com/amgdev9/kotlin-lsp/actions/workflows/push.yml)
[![Chat](https://img.shields.io/badge/chat-on%20discord-7289da)](https://discord.gg/mSYevKDnA5)

This is an implementation of the [Language Server Protocol](https://microsoft.github.io/language-server-protocol/specification) for the [Kotlin](https://kotlinlang.org) programming language, leveraging the Kotlin [Analysis API](https://github.com/JetBrains/kotlin/blob/master/docs/analysis/analysis-api/analysis-api.md) to provide real time diagnostics, syntax and semantic analysis of Kotlin source files and libraries.

## Current status

> ⚠️ **Unmaintained**
>
> This repository is no longer actively maintained. Feel free to fork it and continue development or adapt it for your own needs.

Right now, this language server is at its infancy and thus not ready to use for production environments (yet). As of now, this language server is being prepared to analyse its own codebase, with upcoming support for other projects. Here are the most important steps to make in order to improve its usability:

- Integration with build systems: right now we have 2 integrations available:
    - Gradle
    - File-based: for other build systems, you can write a `.kotlinlsp-modules.json` file at the root of your project with the modules and dependencies it contains. You have an example at `org.kotlinlsp.setup.Scenario.Kt`

- Indexing solution: to provide features like autocomplete and search references, as well as caching to improve analysis performance, we need to create an index where we store all the references used in the project. For this we are using multiple key value stores using [RocksDB](https://rocksdb.org) on disk and perform a background indexing of the whole project, and incrementally update it as the user modifies the source files. One of the goals of this LS is to provide a fast startup time, so diagnostics are reported as quick as possible.

### Implemented features
- ✅ Real time diagnostics: working for this codebase
- ✅ Hover: fully working
- 🚧 Go to definition: working (with decompilation support), needs polish when resolving to kotlin libraries and JDK classes
- 🚧 Build system integration: there is support for
    * Gradle projects (single and multi module) 
    * Single module Android projects (uses debug variant and does not handle source set merging yet)
    * Needs work on:
        * Multimodule Android projects
        * KMP projects (targeting JVM, native target needs investigation on how to do it)

## Installing
We provide a distribution zip file, which you can download from [GitHub Releases](https://github.com/amgdev9/kotlin-lsp/releases/latest). Alternatively, there are unofficial methods to install it, provided by the community:
- Nix: https://tangled.sh/@weethet.bsky.social/nix-packages, accessible via `packages.${system}.kotlin-lsp` or in an overlay

## Building and running

To build the language server, just run the `./scripts/build.sh` script at the root directory, which compiles the project using gradle, packs it as a distribution zip and decompresses it in the `./lsp-dist` folder. Once built, you need to integrate it in a code editor to test its functionality. For example, in neovim the following config can be used:

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

After that, run the code editor in a kotlin file from this project and you should see diagnostics being reported. In the `Log.kt` file you can configure the verbosity of the logs.

## Running tests

To run the tests, just run the `./gradlew test` command. The tests are made around the LSP interface so we test against real user interactions, providing a good safety net in case of refactoring or updating dependencies.

## Contributions

Contributions are welcome! I try to improve this language server in my spare time but progress will be slow if I do it all by myself, so the more contributors this project has, the faster the development will be. Feel free to contact me if you want to contribute, have any doubts about how to start or if you need some more context about the Analysis API (which I'm not an expert, but I can provide my own research to help the development of the project).

Feel free to create issues and submit pull requests, I'll answer them as soon as I can.

## Resources

To help in the development of this project, these resources are extremely valuable:
- [Kotlin Analysis API](https://github.com/JetBrains/kotlin/tree/master/analysis): especially the standalone platform, which is a static read only platform implementation we can use as a baseline
- [IntelliJ IDEA Kotlin plugin](https://github.com/JetBrains/intellij-community/tree/master/plugins/kotlin): the kotlin plugin implements the Analysis API as well implementing the platform interface, so we have it as a base

## Sponsor this project

If you want to economically support this project, I accept donations via 

[!["Buy Me A Coffee"](https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png)](https://www.buymeacoffee.com/amgdev9)

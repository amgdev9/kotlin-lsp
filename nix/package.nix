{
  lib,
  stdenv,
  gradle,
  unzip,
  nix-gitignore,
  makeWrapper,
  jre,
  versionCheckHook,
}:
stdenv.mkDerivation (finalAttrs: {
  pname = "kotlin-lsp";
  version = "0.1";

  src = nix-gitignore.gitignoreSource "/gradle.properties\n" ../.;

  nativeBuildInputs = [
    gradle
    unzip
    makeWrapper
  ];

  mitmCache = gradle.fetchDeps {
    inherit (finalAttrs) pname;
    data = ./deps.json;
  };

  __darwinAllowLocalNetworking = true;

  gradleBuildTask = "distZip";

  doCheck = true;

  installPhase = ''
    mkdir -p $out/share/kotlin-lsp $out/bin
    unzip -j app/build/distributions/app-*.zip 'app-*/lib/*' -d $out/share/kotlin-lsp

    makeWrapper ${jre}/bin/java $out/bin/kotlin-lsp \
      --add-flags "-cp \"$out/share/kotlin-lsp/*\" org.kotlinlsp.MainKt"
  '';

  doInstallCheck = true;
  versionCheckProgram = "${placeholder "out"}/bin/${finalAttrs.meta.mainProgram}";
  versionCheckProgramArg = "--version";
  nativeInstallCheckInputs = [ versionCheckHook ];

  meta = {
    description = "Kotlin language server";
    license = lib.licenses.mit;
    maintainers = [ lib.maintainers.WeetHet ];
    mainProgram = "kotlin-lsp";
  };
})

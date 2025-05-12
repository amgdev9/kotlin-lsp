{
  inputs = {
    nixpkgs.url = "https://channels.nixos.org/nixpkgs-unstable/nixexprs.tar.xz";
    flake-parts = {
      url = "github:hercules-ci/flake-parts";
      inputs.nixpkgs-lib.follows = "nixpkgs";
    };
    build-gradle-application = {
      url = "github:raphiz/buildGradleApplication";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    inputs@{ self, flake-parts, ... }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      systems = [
        "x86_64-linux"
        "x86_64-darwin"
        "aarch64-linux"
        "aarch64-darwin"
      ];

      flake = {
        overlays.default = final: _: {
          kotlin-lsp = final.callPackage ./nix/package.nix { };
        };
      };

      perSystem =
        args@{ self', pkgs, ... }:
        {
          _module.args.pkgs = import inputs.nixpkgs {
            inherit (args) system;
            overlays = [
              inputs.build-gradle-application.overlays.default
              self.overlays.default
            ];
          };
          packages = {
            kotlin-lsp = pkgs.kotlin-lsp;
            default = self'.packages.kotlin-lsp;
          };
          devShells.default = pkgs.mkShell {
            packages = [ pkgs.gradle ];
          };
        };
    };
}

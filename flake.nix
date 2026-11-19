{
  description = "atop - a clean root system monitor for Android";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    # Without this devshell drags in (and evaluates against) its own nixpkgs
    # pin -- the upstream `nix flake init -t github:numtide/devshell` template
    # leaves it out.
    devshell.url = "github:numtide/devshell";
    devshell.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { self, nixpkgs, flake-utils, devshell }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ devshell.overlays.default ];
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        buildTools = "36.0.0";
        android = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "36" ];
          buildToolsVersions = [ buildTools ];
          includeEmulator = false;
          includeNDK = false;
          includeSystemImages = false;
        };
        sdk = "${android.androidsdk}/libexec/android-sdk";
      in
      {
        devShells.default = pkgs.devshell.mkShell {
          imports = [ (pkgs.devshell.importTOML ./devshell.toml) ];

          packages = [ pkgs.jdk21 pkgs.gradle android.androidsdk ];

          env = [
            { name = "ANDROID_HOME"; value = sdk; }
            { name = "ANDROID_SDK_ROOT"; value = sdk; }
            { name = "JAVA_HOME"; value = pkgs.jdk21.home; }
            # The aapt2 that AGP pulls from Maven is a generic-Linux binary and
            # cannot run on NixOS; point it at the patched one from the SDK.
            {
              name = "GRADLE_OPTS";
              value = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/${buildTools}/aapt2";
            }
          ];
        };
      });
}

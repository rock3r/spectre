class Spectre < Formula
  desc "Agent-facing CLI and MCP server for Spectre Compose Desktop automation"
  homepage "https://github.com/rock3r/spectre"
  version "0.5.0"

  on_macos do
    if Hardware::CPU.arm?
      url "https://github.com/rock3r/spectre/releases/download/v0.5.0/spectre-macosArm64.zip"
      sha256 "c0794a96ec5718a46e7d4137930e1ab611a8f5a34c6e9c7b9b1e44b52ccaf1a6"
    else
      url "https://github.com/rock3r/spectre/releases/download/v0.5.0/spectre-macosX64.zip"
      sha256 "3b5a08b3167bf4fa2125d6cc05a0c7f63214d8091540c9ba40a722f4c5d50047"
    end
  end

  # Keep jlink @rpath dylib IDs intact during fix_dynamic_linkage (#390).
  preserve_rpath

  def install
    # Homebrew strips a single top-level directory when staging, so accept both
    # nested (archive as shipped) and top-level (post-strip) layouts.
    app = Dir["spectre-cli-*/Spectre.app"].first || Dir["Spectre.app"].first
    odie "missing Spectre.app in release archive" if app.nil?
    libexec.install app
    # Roast derives config paths from argv[0]; a bin symlink makes those paths
    # nonsense. Install a wrapper that execs the real bundle binary instead.
    (bin/"spectre").write <<~SH
      #!/bin/sh
      exec "#{libexec}/Spectre.app/Contents/MacOS/spectre" "$@"
    SH
    (bin/"spectre").chmod 0755
  end

  def post_install
    # fix_dynamic_linkage runs before post_install and can still rewrite nested
    # jlink Mach-Os even with preserve_rpath (e.g. stripping duplicate
    # @loader_path rpaths), then ad-hoc re-sign them. That breaks the outer
    # Developer ID seal (Gatekeeper "damaged" — #390). Re-stage the notarized
    # app from the release zip after linkage fix so sealed resources match.
    restore_signed_app!
  end

  def restore_signed_app!
    cached = cached_download
    odie "missing cached download for Spectre.app seal restore" if cached.nil? || !cached.exist?

    require "tmpdir"
    Dir.mktmpdir("spectre-app-restore") do |tmpdir|
      system "ditto", "-x", "-k", cached.to_s, tmpdir
      restored =
        Dir["#{tmpdir}/spectre-cli-*/Spectre.app"].first || Dir["#{tmpdir}/Spectre.app"].first
      odie "missing Spectre.app in release archive for seal restore" if restored.nil?

      target = libexec/"Spectre.app"
      rm_r target if target.exist?
      system "ditto", restored, target.to_s
    end
  end

  test do
    assert_match "Usage:", shell_output("#{bin}/spectre --help")
  end
end

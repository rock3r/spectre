class Spectre < Formula
  desc "Agent-facing CLI and MCP server for Spectre Compose Desktop automation"
  homepage "https://github.com/rock3r/spectre"
  version "0.4.1"

  on_macos do
    if Hardware::CPU.arm?
      url "https://github.com/rock3r/spectre/releases/download/v0.4.1/spectre-macosArm64.zip"
      sha256 "1c30cf1c7447d30605efa4861128de2a56c90308ae3bfcd8bdae713d17b8cd46"
    else
      url "https://github.com/rock3r/spectre/releases/download/v0.4.1/spectre-macosX64.zip"
      sha256 "70878acdbf9b7c2b2497ede5afe90695bf22ccdefa0cc3bf245536f2b5277fd2"
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

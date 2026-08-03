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

  test do
    assert_match "Usage:", shell_output("#{bin}/spectre --help")
  end
end

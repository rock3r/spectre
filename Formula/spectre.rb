class Spectre < Formula
  desc "Agent-facing CLI and MCP server for Spectre Compose Desktop automation"
  homepage "https://github.com/rock3r/spectre"
  version "0.4.0"

  on_macos do
    if Hardware::CPU.arm?
      url "https://github.com/rock3r/spectre/releases/download/v0.4.0/spectre-macosArm64.zip"
      sha256 "6c6ecef4e7854774592a1bb42257b31245012daf7e9e04be18b48cfd4321927a"
    else
      url "https://github.com/rock3r/spectre/releases/download/v0.4.0/spectre-macosX64.zip"
      sha256 "34b06611cd61f559bc388aa3546b257c3524b130415cb0795756adcaca574883"
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

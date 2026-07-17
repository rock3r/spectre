class Spectre < Formula
  desc "Agent-facing CLI and MCP server for Spectre Compose Desktop automation"
  homepage "https://github.com/rock3r/spectre"
  version "0.3.0"

  on_macos do
    if Hardware::CPU.arm?
      url "https://github.com/rock3r/spectre/releases/download/v0.3.0/spectre-macosArm64.zip"
      sha256 "2ae6de95f0981a14dacfb95f05196af1ea691f49b4a4404cef7d278cf2f64af2"
    else
      url "https://github.com/rock3r/spectre/releases/download/v0.3.0/spectre-macosX64.zip"
      sha256 "f22de6e7d6725aa46d8fe9d0f367b580128776d8987339257318860128f188ad"
    end
  end

  def install
    app = Dir["spectre-cli-*/Spectre.app"].first
    odie "missing Spectre.app in release archive" if app.nil?
    libexec.install app
    bin.install_symlink libexec/"Spectre.app/Contents/MacOS/spectre"
  end

  test do
    assert_match "Usage:", shell_output("#{bin}/spectre --help")
  end
end

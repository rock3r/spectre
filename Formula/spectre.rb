class Spectre < Formula
  desc "Agent-facing CLI and MCP server for Spectre Compose Desktop automation"
  homepage "https://github.com/rock3r/spectre"
  version "0.3.0"

  on_macos do
    if Hardware::CPU.arm?
      url "https://github.com/rock3r/spectre/releases/download/v0.3.0/spectre-macosArm64.zip"
      sha256 "b798e2d85d051b7b80b7a49a6e89cc102ad79754878f48bc2ed22f9180d9607b"
    else
      url "https://github.com/rock3r/spectre/releases/download/v0.3.0/spectre-macosX64.zip"
      sha256 "342d2a64abc2c2f42770becf69fd3d0e96d1958cb7cb45e563fd6b097643ce1a"
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

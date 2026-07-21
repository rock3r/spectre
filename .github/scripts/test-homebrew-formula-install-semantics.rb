#!/usr/bin/env ruby
# frozen_string_literal: true

# Behavioral contract tests for the Homebrew formula install path.
#
# These would have failed before:
#   - #283 (only nested spectre-cli-*/Spectre.app; Homebrew strip leaves top-level Spectre.app)
#   - #284 (bin.install_symlink of the Roast binary; argv[0]-relative config lookup panics)
#
# App discovery is evaluated from the formula source (not a reimplemented ideal), so a
# nested-only glob regresses the stripped-layout case.
#
# Run via test-generate-cli-package-manifests.sh or directly:
#   ruby .github/scripts/test-homebrew-formula-install-semantics.rb [formula.rb ...]

require "fileutils"
require "tmpdir"
require "pathname"

module SpectreHomebrewInstallSemantics
  module_function

  FORBIDDEN_SYMLINK = 'bin.install_symlink libexec/"Spectre.app/Contents/MacOS/spectre"'

  REQUIRED_SNIPPETS = [
    '(bin/"spectre").write',
    "#!/bin/sh",
    'exec "#{libexec}/Spectre.app/Contents/MacOS/spectre" "$@"',
    '(bin/"spectre").chmod 0755',
    'assert_match "Usage:", shell_output("#{bin}/spectre --help")',
  ].freeze

  # Extract `app = ...` RHS from the formula install method and evaluate it in the
  # current directory (Dir[] is relative to cwd). Only allows Dir[...].first chains.
  def find_app_expression(formula_text)
    match = formula_text.match(/^\s*app = (.+)$/)
    raise "formula install method has no `app = ...` assignment" unless match

    expr = match[1].strip
    unless expr.match?(/\ADir\["[^"]+"\]\.first(?:\s*\|\|\s*Dir\["[^"]+"\]\.first)*\z/)
      raise "refusing to evaluate unexpected app expression: #{expr.inspect}"
    end

    expr
  end

  def find_app(formula_text)
    # rubocop:disable Security/Eval -- expression is allowlisted above to Dir[].first chains only
    eval(find_app_expression(formula_text))
    # rubocop:enable Security/Eval
  end

  def uses_raw_roast_symlink?(formula_text)
    formula_text.include?(FORBIDDEN_SYMLINK)
  end

  def write_wrapper(bin_path, real_binary)
    File.write(
      bin_path,
      <<~SH
        #!/bin/sh
        exec "#{real_binary}" "$@"
      SH
    )
    FileUtils.chmod(0o755, bin_path)
  end

  # Roast-like stub: succeeds only when argv[0] is the real MacOS binary path.
  # Echoes args so tests can prove the wrapper forwards "$@" (formula test uses --help).
  def write_argv0_sensitive_binary(path)
    FileUtils.mkdir_p(File.dirname(path))
    File.write(
      path,
      <<~SH
        #!/bin/sh
        case "$0" in
          */Contents/MacOS/spectre)
            echo "Usage: spectre [<options>] <command>"
            echo "args:$*"
            exit 0
            ;;
          *)
            echo "Unable to read config file $(dirname "$0")/app/$0.json" >&2
            exit 101
            ;;
        esac
      SH
    )
    FileUtils.chmod(0o755, path)
  end

  def stage_app_tree(root, nested:)
    base =
      if nested
        File.join(root, "spectre-cli-0.3.0", "Spectre.app")
      else
        File.join(root, "Spectre.app")
      end
    mac_bin = File.join(base, "Contents", "MacOS", "spectre")
    write_argv0_sensitive_binary(mac_bin)
    base
  end

  def simulate_wrapper_install(stage_dir, cellar_prefix, formula_text)
    app = nil
    Dir.chdir(stage_dir) { app = find_app(formula_text) }
    raise "missing Spectre.app in release archive" if app.nil?

    libexec = File.join(cellar_prefix, "libexec")
    bin = File.join(cellar_prefix, "bin")
    FileUtils.mkdir_p(libexec)
    FileUtils.mkdir_p(bin)
    FileUtils.cp_r(File.join(stage_dir, app), libexec)
    real = File.join(libexec, "Spectre.app", "Contents", "MacOS", "spectre")
    wrapper = File.join(bin, "spectre")
    write_wrapper(wrapper, real)
    { libexec: libexec, bin: bin, wrapper: wrapper, real: real, app: app }
  end
end

def assert!(condition, message)
  raise message unless condition
end

def run_cmd(cmd)
  output = `#{cmd} 2>&1`
  [output, $?.exitstatus]
end

failures = []

def record_failure(failures, name)
  yield
rescue StandardError => e
  failures << "#{name}: #{e.message}"
  warn "FAIL #{name}: #{e.message}"
else
  puts "ok #{name}"
end

formula_paths = ARGV.dup
if formula_paths.empty?
  root = File.expand_path("../..", __dir__)
  formula_paths = [File.join(root, "Formula", "spectre.rb")]
end

formula_paths.each do |path|
  assert!(File.file?(path), "missing formula file #{path}")
  formula_text = File.read(path)
  label = File.basename(path) == "spectre.rb" ? path : path

  record_failure(failures, "formula text contract (#{label})") do
    SpectreHomebrewInstallSemantics::REQUIRED_SNIPPETS.each do |snippet|
      assert!(formula_text.include?(snippet), "#{path} missing required snippet:\n  #{snippet}")
    end
    assert!(
      !SpectreHomebrewInstallSemantics.uses_raw_roast_symlink?(formula_text),
      "#{path} must not use raw bin.install_symlink of the Roast binary"
    )
    expr = SpectreHomebrewInstallSemantics.find_app_expression(formula_text)
    assert!(
      expr.include?('Dir["Spectre.app"].first'),
      "#{path} app discovery must accept top-level Spectre.app (Homebrew strip); got #{expr}"
    )
    assert!(
      expr.include?('Dir["spectre-cli-*/Spectre.app"].first'),
      "#{path} app discovery must accept nested spectre-cli-*/Spectre.app; got #{expr}"
    )
  end

  record_failure(failures, "find_app nested layout (#{label})") do
    Dir.mktmpdir do |tmp|
      SpectreHomebrewInstallSemantics.stage_app_tree(tmp, nested: true)
      Dir.chdir(tmp) do
        app = SpectreHomebrewInstallSemantics.find_app(formula_text)
        assert!(!app.nil?, "expected nested Spectre.app, got nil")
        assert!(
          app == "spectre-cli-0.3.0/Spectre.app",
          "expected spectre-cli-0.3.0/Spectre.app, got #{app.inspect}"
        )
      end
    end
  end

  record_failure(failures, "find_app stripped Homebrew layout (#{label})") do
    Dir.mktmpdir do |tmp|
      SpectreHomebrewInstallSemantics.stage_app_tree(tmp, nested: false)
      Dir.chdir(tmp) do
        app = SpectreHomebrewInstallSemantics.find_app(formula_text)
        assert!(
          !app.nil?,
          "expected top-level Spectre.app after Homebrew strip (this is #283); got nil " \
            "from expression #{SpectreHomebrewInstallSemantics.find_app_expression(formula_text)}"
        )
        assert!(app == "Spectre.app", "expected Spectre.app, got #{app.inspect}")
      end
    end
  end

  record_failure(failures, "find_app missing is nil (#{label})") do
    Dir.mktmpdir do |tmp|
      Dir.chdir(tmp) do
        app = SpectreHomebrewInstallSemantics.find_app(formula_text)
        assert!(app.nil?, "expected nil when Spectre.app is absent")
      end
    end
  end

  record_failure(failures, "install stripped layout + wrapper --help (#{label})") do
    Dir.mktmpdir do |tmp|
      stage = File.join(tmp, "stage")
      cellar = File.join(tmp, "cellar")
      FileUtils.mkdir_p(stage)
      SpectreHomebrewInstallSemantics.stage_app_tree(stage, nested: false)
      result =
        SpectreHomebrewInstallSemantics.simulate_wrapper_install(stage, cellar, formula_text)

      assert!(
        File.directory?(File.join(result[:libexec], "Spectre.app")),
        "libexec must contain Spectre.app (empty cellar was a real #283 symptom)"
      )
      assert!(File.executable?(result[:real]), "real MacOS binary must be executable")
      assert!(
        !File.symlink?(result[:wrapper]),
        "bin/spectre must not be a symlink of the Roast binary"
      )
      wrapper_body = File.read(result[:wrapper])
      assert!(wrapper_body.include?("#!/bin/sh"), "wrapper must be a shell script")
      assert!(
        wrapper_body.include?(%(exec "#{result[:real]}")),
        "wrapper must exec the real bundle binary path"
      )

      # Match formula test do: shell_output("#{bin}/spectre --help") — twice.
      out1, status1 = run_cmd("#{result[:wrapper].inspect} --help")
      out2, status2 = run_cmd("#{result[:wrapper].inspect} --help")
      assert!(status1.zero?, "wrapper --help run1 exit #{status1}: #{out1}")
      assert!(status2.zero?, "wrapper --help run2 exit #{status2}: #{out2}")
      assert!(out1.include?("Usage:"), "run1 stdout must match formula test: #{out1.inspect}")
      assert!(out2.include?("Usage:"), "run2 stdout must match formula test: #{out2.inspect}")
      assert!(out1.include?("args:--help"), "wrapper must forward --help via \"$@\": #{out1.inspect}")
    end
  end

  record_failure(failures, "install nested layout + wrapper --help (#{label})") do
    Dir.mktmpdir do |tmp|
      stage = File.join(tmp, "stage")
      cellar = File.join(tmp, "cellar")
      FileUtils.mkdir_p(stage)
      SpectreHomebrewInstallSemantics.stage_app_tree(stage, nested: true)
      result =
        SpectreHomebrewInstallSemantics.simulate_wrapper_install(stage, cellar, formula_text)
      assert!(result[:app] == "spectre-cli-0.3.0/Spectre.app", "unexpected app path #{result[:app]}")
      out, status = run_cmd("#{result[:wrapper].inspect} --help")
      assert!(status.zero? && out.include?("Usage:"), "nested install wrapper failed: #{out}")
      assert!(out.include?("args:--help"), "wrapper must forward --help: #{out.inspect}")
    end
  end
end

# Symlink entry point breaks argv[0]-sensitive launcher (documents #284 invariant).
record_failure(failures, "raw bin symlink fails argv0-sensitive binary") do
  Dir.mktmpdir do |tmp|
    stage = File.join(tmp, "stage")
    FileUtils.mkdir_p(stage)
    SpectreHomebrewInstallSemantics.stage_app_tree(stage, nested: false)
    libexec = File.join(tmp, "libexec")
    bin = File.join(tmp, "bin")
    FileUtils.mkdir_p(libexec)
    FileUtils.mkdir_p(bin)
    FileUtils.cp_r(File.join(stage, "Spectre.app"), libexec)
    real = File.join(libexec, "Spectre.app", "Contents", "MacOS", "spectre")
    link = File.join(bin, "spectre")
    FileUtils.ln_s(real, link)
    out, status = run_cmd(link.inspect)
    assert!(!status.zero?, "symlink entry must fail for argv0-sensitive binary, got 0: #{out}")
    assert!(
      out.include?("Unable to read config file") || status == 101,
      "expected Roast-style config panic via symlink, got: #{out.inspect}"
    )
  end
end

if failures.empty?
  puts "All Homebrew formula install semantics tests passed."
  exit 0
end

warn "\n#{failures.size} failure(s):"
failures.each { |f| warn "  - #{f}" }
exit 1

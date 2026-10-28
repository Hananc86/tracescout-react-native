require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name            = "TraceScoutRN"
  s.version         = package["version"]
  s.summary         = package["description"]
  s.homepage        = package["homepage"]
  s.license         = { :type => "MIT", :file => "LICENSE" }
  s.authors         = { "TraceScout" => "support@tracescout.com" }
  s.platforms       = { :ios => "13.4" }
  s.source          = { :git => "https://github.com/Hananc86/tracescout-react-native.git", :tag => "v#{s.version}" }

  # The capture sources and the React adapter. The fixture view controllers in
  # the private development tree are deliberately not part of this package.
  s.source_files    = "ios/TraceScoutRN/**/*.swift", "ios/RNBridge/**/*.{h,m,swift}"
  s.swift_version   = "5.9"

  s.dependency "React-Core"
end

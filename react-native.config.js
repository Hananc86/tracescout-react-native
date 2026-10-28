// React Native autolinking metadata. The host application does not register
// the native package by hand; the generated package list reads this.
module.exports = {
  dependency: {
    platforms: {
      android: {
        sourceDir: './android',
        packageImportPath: 'import com.tracescout.rn.react.TraceScoutRnPackage;',
        packageInstance: 'new TraceScoutRnPackage()',
      },
      ios: {
        podspecPath: __dirname + '/TraceScoutRN.podspec',
      },
    },
  },
};

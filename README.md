このFOPは日本語の縦書き、ルビ、自動縮小をサポートします。

## yao-2_11

Based on Apache FOP tag `2_11` (`eb1c0dc249d5106349ee9f85d90942e6ddf681d4`).
The five custom commits from `yao-2_9` have been ported with their original
commit IDs recorded in the commit messages:

- `fox:shrink-to-fit` for block containers, including list blocks.
- The custom letter-spacing adjustment.
- Japanese vertical writing, ruby examples, and sample documents.

FOP-3146, FOP-3148, and FOP-3150 are already included in the upstream base.
The font-mapper merge retains upstream's `getRealFont()` calls alongside the
custom `isVertical` arguments.

Build and run the full test suite (validated with JDK 8):

```sh
mvn -B clean verify
```

Run the shrink-to-fit layout regression separately:

```sh
mvn -B -pl fop-core -am -Dtest=LayoutEngineTestCase -DfailIfNoTests=false \
  -Dfop.layoutengine.single=fox_shrink-to-fit.xml test
```

The [Japanese example](fop/examples/fo/japanese/vertical_writing.fo) includes
vertical writing, ruby, and automatic shrinking. For PDF rendering, configure
its `Noto Sans CJK SC` font in your FOP font configuration; the migration smoke
test used a standalone `NotoSansCJKsc-Regular.otf` font and produced four pages.

## Samples

![vertical](https://raw.githubusercontent.com/chunlinyao/fop/yao/vertical.png)
![ruby](https://raw.githubusercontent.com/chunlinyao/fop/yao/ruby.png)
![shrink](https://raw.githubusercontent.com/chunlinyao/fop/yao/shrink.png)

サンプルファイルをダウンロードできる。
(https://raw.githubusercontent.com/chunlinyao/fop/yao/sample.pdf)

/*
Copyright 2020 Morgan Stanley

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */

package morphir.ir.fuzzer

import morphir.ir.name.Name
import morphir.ir.path.Path
import morphir.ir.FQName

trait FQNameFuzzers {
  implicit def fuzzFQName(implicit pathFuzzer: Fuzzer[Path], nameFuzzer: Fuzzer[Name]): Fuzzer[FQName] =
    for {
      packagePath <- pathFuzzer
      modulePath  <- pathFuzzer
      localName   <- nameFuzzer
    } yield FQName(packagePath, modulePath, localName)
}

object FQNameFuzzers extends FQNameFuzzers

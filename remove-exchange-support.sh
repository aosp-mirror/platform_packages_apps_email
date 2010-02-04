#!/bin/bash
#
# Copyright (C) 2010 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#
# You can remove exchange by running this script.
#

set -e # fail fast


# Step 0. Make sure we're in the right directory, and the user really wants it.

if [[ ! -d src/com/android/email/ ]] ; then
  echo "Run the script in the root of the email source tree." 1>&2
  exit 1
fi

echo ""
echo -n "Do you wish to remove exchange support from the email app? (y/N):"

read answer
if [[ "$answer" != y ]] ; then
  echo "Aborted." 1>&2
  exit 1
fi


# Step 1. Remove all Exchange related packages.

rm -fr src/com/android/exchange/ \
       tests/src/com/android/exchange/


# Step 2. Remove lines surrounded by START-EXCHANGE and END-EXCHANGE

find . \( -name '*.java' -o -name '*.xml' -o -name 'Android.mk' \) -print0 |
    xargs -0 sed -i -e '/EXCHANGE-REMOVE-SECTION-START/,/EXCHANGE-REMOVE-SECTION-END/d'


# Step 3. Remove all imports from com.android.exchange (and its subpackages).

find . -name '*.java' -print0 |
    xargs -0 sed -i -e '/^import com\.android\.exchange/d'


echo ""
echo "Exchange support has been successfully removed."

exit 0

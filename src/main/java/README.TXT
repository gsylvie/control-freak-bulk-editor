
## Steps to build and use:

1. Compile the code in this directory:  javac \*.java

2. Obtain a HTTP Authorization Token from Bitbucket and save it to "./.controlFreak.tok"

3. Edit bulkdedits.json to prepare your bulk-edit !

4. Run the code:


java -cp . ControlFreakBulkUpdate <USER> <BITBUCKET-URL> bulkedits.json


Note:  The <USER> must correspond to the HTTP Token saved during step #2,
and must have "admin" permission to the repositories you plan to bulk-edit
Control-Freak's settings on.



## Alternative steps to build and use (using Maven): 

Alternatively you can build this code using Maven, since we've provided a "pom.xml" for those
that prefer that kind of thing:

1. cd ../../..

2. mvn clean

3. mvn package

4. Obtain a HTTP Authorization Token from Bitbucket and save it to "./.controlFreak.tok"

5. Edit bulkdedits.json to prepare your bulk-edit !

6. Run the code:

java -jar target/control-freak-bulk-editor-2023.11.09.jar  <USER> <BITBUCKET-URL> bulkedits.json


Note:  The <USER> must correspond to the HTTP Token saved during step #2,
and must have "admin" permission to the repositories you plan to bulk-edit
Control-Freak's settings on.
 

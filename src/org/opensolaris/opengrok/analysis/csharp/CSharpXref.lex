/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").  
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

/*
 * Copyright (c) 2011, 2016, Oracle and/or its affiliates. All rights reserved.
 * Portions Copyright (c) 2017, Chris Fraire <cfraire@me.com>.
 */

/*
 * Cross reference a C# file
 * @author Christoph Hofmann - ChristophHofmann AT gmx dot de
 */

package org.opensolaris.opengrok.analysis.csharp;
import org.opensolaris.opengrok.analysis.JFlexXref;
import java.io.IOException;
import java.io.Writer;
import java.io.Reader;
import org.opensolaris.opengrok.web.Util;

%%
%public
%class CSharpXref
%extends JFlexXref
%unicode
%ignorecase
%int
%include CommonXref.lexh
%{
  // TODO move this into an include file when bug #16053 is fixed
  @Override
  protected int getLineNumber() { return yyline; }
  @Override
  protected void setLineNumber(int x) { yyline = x; }
%}

CsharpEOL = {EOL}|\u2028|\u2029|\u000B|\u000C|\u0085
Identifier = [a-zA-Z_] [a-zA-Z0-9_]+

File = [a-zA-Z]{FNameChar}* "." ([chts]|"cs")

Number = (0[xX][0-9a-fA-F]+|[0-9]+\.[0-9]+|[0-9]+)(([eE][+-]?[0-9]+)?[ufdlUFDL]*)?

//ClassName = ({Identifier} ".")* {Identifier}
//ParamName = {Identifier} | "<" {Identifier} ">"

%state  STRING COMMENT SCOMMENT QSTRING VSTRING

%include Common.lexh
%include CommonURI.lexh
%include CommonPath.lexh
%%
<YYINITIAL>{
 \{     { incScope(); writeUnicodeChar(yycharat(0)); }
 \}     { decScope(); writeUnicodeChar(yycharat(0)); }
 \;     { endScope(); writeUnicodeChar(yycharat(0)); }

{Identifier} {
    String id = yytext();
    writeSymbol(id, Consts.kwd, yyline);
}

"<" ({File} | {FPath}) ">" {
        out.write("&lt;");
        String path = yytext();
        path = path.substring(1, path.length() - 1);
        out.write("<a href=\""+urlPrefix+"path=");
        out.write(path);
        appendProject();
        out.write("\">");
        out.write(path);
        out.write("</a>");
        out.write("&gt;");
}

/*{Hier}
    { out.write(Util.breadcrumbPath(urlPrefix+"defs=",yytext(),'.'));}
*/
{Number}        { out.write("<span class=\"n\">"); out.write(yytext()); out.write("</span>"); }

 \"     { yybegin(STRING);out.write("<span class=\"s\">\"");}
 \'     { yybegin(QSTRING);out.write("<span class=\"s\">\'");}
 "/*"   { yybegin(COMMENT);out.write("<span class=\"c\">/*");}
 "//"   { yybegin(SCOMMENT);out.write("<span class=\"c\">//");}
 "@\""  { yybegin(VSTRING);out.write("<span class=\"s\">@\"");}  
}

<STRING> {
 \" {WhiteSpace} \"  { out.write(yytext());}
 \"     { yybegin(YYINITIAL); out.write("\"</span>"); }
 \\\\   { out.write("\\\\"); }
 \\\"   { out.write("\\\""); }
}

<QSTRING> {
 "\\\\" { out.write("\\\\"); }
 "\\\'" { out.write("\\\'"); }
 \' {WhiteSpace} \' { out.write(yytext()); }
 \'     { yybegin(YYINITIAL); out.write("'</span>"); }
}

<VSTRING> {
 \" {WhiteSpace} \"  { out.write(yytext());}
 "\"\""   { out.write("\"\""); }
 \"       { yybegin(YYINITIAL); out.write("\"</span>"); } 
 \\       { out.write("\\"); }
}

<COMMENT> {
"*/"    { yybegin(YYINITIAL); out.write("*/</span>"); }
}

<SCOMMENT> {
  {WhspChar}*{CsharpEOL} {
    yybegin(YYINITIAL); out.write("</span>");
    startNewLine();
  }
}

<YYINITIAL, STRING, COMMENT, SCOMMENT, QSTRING, VSTRING> {
"&"     {out.write( "&amp;");}
"<"     {out.write( "&lt;");}
">"     {out.write( "&gt;");}
{WhspChar}*{CsharpEOL}      { startNewLine(); }
 {WhiteSpace}   { out.write(yytext()); }
 [!-~]  { out.write(yycharat(0)); }
 [^\n]      { writeUnicodeChar(yycharat(0)); }
}

<STRING, COMMENT, SCOMMENT, QSTRING, VSTRING> {
{FPath}
        { out.write(Util.breadcrumbPath(urlPrefix+"path=",yytext(),'/'));}

{File}
        {
        String path = yytext();
        out.write("<a href=\""+urlPrefix+"path=");
        out.write(path);
        appendProject();
        out.write("\">");
        out.write(path);
        out.write("</a>");}

{BrowseableURI}    {
          appendLink(yytext(), true);
        }

{FNameChar}+ "@" {FNameChar}+ "." {FNameChar}+
        {
          writeEMailAddress(yytext());
        }
}

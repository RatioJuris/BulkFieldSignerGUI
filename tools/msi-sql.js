// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
// Runs SQL statements against an MSI database with the Windows Installer automation interface.
// Usage: cscript //nologo tools\msi-sql.js <file.msi> <statement> [<statement> ...]
// SELECT results are printed one row per line, fields separated by " | ". Other statements are committed.
var args = WScript.Arguments;
var installer = new ActiveXObject("WindowsInstaller.Installer");
var write = false;
for (var i = 1; i < args.length; i++) if (!/^\s*SELECT/i.test(args(i))) write = true;
var database = installer.OpenDatabase(args(0), write ? 1 : 0);
for (var i = 1; i < args.length; i++) {
    var view = database.OpenView(args(i));
    view.Execute();
    if (/^\s*SELECT/i.test(args(i))) {
        var record;
        while ((record = view.Fetch()) != null) {
            var fields = [];
            for (var f = 1; f <= record.FieldCount; f++) fields.push(record.StringData(f));
            WScript.Echo(fields.join(" | "));
        }
    }
    view.Close();
}
if (write) database.Commit();

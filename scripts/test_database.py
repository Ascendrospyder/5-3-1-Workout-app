"""SQLite smoke tests using the schema and queries extracted from WorkoutDb.kt.

These exercise SQLite migrations, queries and rollback locally. They supplement
Kotlin unit tests; they do not replace Android import/file-picker/device testing.
"""
from pathlib import Path
import json
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "app/src/main/java/com/example/fivethreeonelifter/WorkoutDb.kt").read_text(encoding="utf-8")
CREATES = re.findall(r'db\.execSQL\("""\s*(CREATE TABLE.*?)"""\.trimIndent\(\)\)', SOURCE, re.S)
ALTERS = re.findall(r'db\.execSQL\("(ALTER TABLE[^"\n]+)"\)', SOURCE)
SETTINGS = re.search(r'db\.execSQL\("(CREATE TABLE settings[^"\n]+)"\)', SOURCE).group(1)

def create_v2():
    database = sqlite3.connect(":memory:")
    database.execute("PRAGMA foreign_keys=ON")
    for sql in CREATES:
        if "CREATE TABLE template_sets" not in sql:
            database.execute(sql)
    database.execute(SETTINGS)
    database.execute("INSERT INTO settings VALUES ('week','1'),('cycle','1')")
    database.commit()
    return database

def migrate(database):
    with database:
        for sql in ALTERS:
            database.execute(sql)
        database.execute(next(sql for sql in CREATES if "CREATE TABLE template_sets" in sql))

def query(method):
    start = SOURCE.index(f"fun {method}(")
    end = re.search(r"\n    (?:private )?fun ", SOURCE[start + 4:])
    body = SOURCE[start:start + 4 + end.start()] if end else SOURCE[start:]
    return re.search(r'(?:val sql = )"""\s*(SELECT.*?)"""\.trimIndent\(\)', body, re.S).group(1)

def dump(database):
    names = [row[0] for row in database.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT GLOB 'sqlite_*' ORDER BY name")]
    tables = {}
    for name in names:
        cursor = database.execute(f'SELECT * FROM "{name}"')
        columns = [column[0] for column in cursor.description]
        tables[name] = [dict(zip(columns, row)) for row in cursor]
    return json.loads(json.dumps(tables))

class DatabaseTests(unittest.TestCase):
    def setUp(self):
        self.db = create_v2()
        self.db.execute("INSERT INTO exercises(id,name,mode,default_weight) VALUES (1,'Row','MANUAL',20)")
        self.db.execute("INSERT INTO templates VALUES (1,'A'),(2,'B')")
        self.db.execute("INSERT INTO template_exercises(template_id,exercise_id,sort_order) VALUES (1,1,0),(2,1,0)")
        self.db.execute("INSERT INTO workout_schedule VALUES (3,1)")
        self.db.execute("INSERT INTO workouts VALUES (1,1,'A',100,1000,'COMPLETED'),(2,1,'A',1100,2000,'COMPLETED'),(3,1,'A',3000,NULL,'ACTIVE')")
        self.db.execute("INSERT INTO workout_exercises VALUES (1,1,1,'Row','MANUAL',0),(2,2,1,'Row','MANUAL',0),(3,3,1,'Row','MANUAL',0)")
        self.db.execute("INSERT INTO workout_sets VALUES (1,1,1,'8-12',NULL,20,25,10,1),(2,2,1,'8-12',NULL,20,30,8,1),(3,3,1,'8-12',NULL,20,30,8,0)")
        self.db.commit()
        migrate(self.db)

    def tearDown(self):
        self.db.close()

    def test_additive_migration_keeps_existing_history(self):
        self.assertEqual(3, self.db.execute("SELECT COUNT(*) FROM workouts").fetchone()[0])
        self.assertEqual((25.0, 10), self.db.execute("SELECT actual_weight,actual_reps FROM workout_sets WHERE id=1").fetchone())
        self.assertEqual(0, self.db.execute("SELECT removed FROM workout_exercises WHERE id=1").fetchone()[0])
        self.assertIsNone(self.db.execute("SELECT workout_week FROM workouts WHERE id=3").fetchone()[0])

    def test_deleted_template_hides_reminders_and_undo_keeps_membership(self):
        self.db.execute("UPDATE templates SET deleted=1 WHERE id=1")
        scheduled = self.db.execute(query("getScheduledDay"), (3,)).fetchone()
        self.assertIsNone(scheduled[2])
        self.db.execute("UPDATE templates SET deleted=0 WHERE id=1")
        self.assertEqual("A", self.db.execute(query("getScheduledDay"), (3,)).fetchone()[2])
        self.assertEqual(1, self.db.execute("SELECT COUNT(*) FROM template_exercises WHERE template_id=1").fetchone()[0])

    def test_removed_exercises_do_not_count_and_undo_restores_sets(self):
        self.assertEqual((1, 1), self.db.execute(query("getWorkout"), (1,)).fetchone()[5:7])
        self.db.execute("UPDATE workout_exercises SET removed=1 WHERE id=1")
        self.assertEqual((0, 0), self.db.execute(query("getWorkout"), (1,)).fetchone()[5:7])
        self.db.execute("UPDATE workout_exercises SET removed=0 WHERE id=1")
        self.assertEqual((1, 1), self.db.execute(query("getWorkout"), (1,)).fetchone()[5:7])

    def test_previous_session_excludes_current_and_removed_entries(self):
        sql = query("previousSessionSets").replace("$identity", "we.exercise_id = ?")
        parameters = (1, "COMPLETED", 3, 3000, 3000, 3)
        self.assertEqual(2, self.db.execute(sql, parameters).fetchone()[0])
        self.db.execute("UPDATE workout_exercises SET removed=1 WHERE id=2")
        self.assertEqual(1, self.db.execute(sql, parameters).fetchone()[0])

    def test_progress_reflects_history_corrections(self):
        sql = query("getExerciseProgress").replace("$identity", "we.exercise_id = ?")
        before = self.db.execute(sql, ("COMPLETED", 1)).fetchall()
        self.db.execute("UPDATE workout_sets SET actual_weight=40,actual_reps=12 WHERE id=2")
        after = self.db.execute(sql, ("COMPLETED", 1)).fetchall()
        self.assertEqual((30.0, 8), before[0][2:4])
        self.assertEqual((40.0, 12), after[0][2:4])
        self.assertAlmostEqual(56.0, after[0][4])

    def test_per_template_defaults_do_not_change_other_templates(self):
        self.db.execute("INSERT INTO template_sets VALUES (1,1,1,'10',25,10),(2,1,1,'8',30,8)")
        self.db.execute("DELETE FROM template_sets WHERE template_id=1")
        self.db.execute("INSERT INTO template_sets VALUES (1,1,1,'12',35,12)")
        self.assertEqual((30.0, 8), self.db.execute("SELECT planned_weight,actual_reps FROM template_sets WHERE template_id=2").fetchone())
        self.assertEqual(20.0, self.db.execute("SELECT default_weight FROM exercises WHERE id=1").fetchone()[0])

    def test_logical_roundtrip_preserves_ids_nulls_and_relations(self):
        tables = dump(self.db)
        other = create_v2()
        try:
            migrate(other)
            other.execute("DELETE FROM settings")
            order = ["exercises", "templates", "template_exercises", "workouts", "workout_exercises", "workout_sets", "settings", "workout_schedule", "template_sets"]
            with other:
                for table in order:
                    for row in tables[table]:
                        columns = ",".join(f'"{key}"' for key in row)
                        placeholders = ",".join("?" for _ in row)
                        other.execute(f'INSERT INTO "{table}" ({columns}) VALUES ({placeholders})', tuple(row.values()))
            self.assertEqual(tables, dump(other))
            self.assertEqual([], other.execute("PRAGMA foreign_key_check").fetchall())
        finally:
            other.close()

    def test_constraint_failure_rolls_back_replacement(self):
        original = dump(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute("DELETE FROM workout_sets")
                self.db.execute("INSERT INTO workout_sets(workout_exercise_id,set_number,target_reps,planned_weight,actual_weight) VALUES (999,1,'8',20,20)")
        self.assertEqual(original, dump(self.db))

if __name__ == "__main__":
    unittest.main(verbosity=2)

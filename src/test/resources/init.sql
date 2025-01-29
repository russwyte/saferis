-- Create the test table
CREATE TABLE test_table (
    name VARCHAR(100) NOT NULL,
    age INT,
    email VARCHAR(100)
);

-- Insert sample data into the test table
INSERT INTO test_table (name, age, email) VALUES
('Alice', 30, 'alice@example.com'),
('Bob', 25, 'bob@example.com'),
('Charlie', null, 'charlie@example.com'),
('Diana', 28, null);
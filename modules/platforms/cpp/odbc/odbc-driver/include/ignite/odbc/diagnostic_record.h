/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef _IGNITE_ODBC_DRIVER_DIAGNOSTIC_RECORD
#define _IGNITE_ODBC_DRIVER_DIAGNOSTIC_RECORD

#include <stdint.h>

#include <vector>

#include <ignite/common/common.h>
#include "ignite/odbc/common_types.h"
#include "ignite/odbc/app/application_data_buffer.h"

namespace ignite
{
    namespace odbc
    {
        /**
         * Status diagnostic record.
         */
        class StatusDiagnosticRecord
        {
        public:
            /**
             * Default constructor.
             */
            StatusDiagnosticRecord();

            /**
             * Constructor.
             *
             * @param sqlState SQL state code.
             * @param message Message.
             * @param connectionName Connection name.
             * @param serverName Server name.
             * @param rowNum Associated row number.
             * @param columnNum Associated column number.
             */
            StatusDiagnosticRecord(SqlState sqlState, const std::string& message,
                const std::string& connectionName, const std::string& serverName,
                int32_t rowNum = 0, int32_t columnNum = 0);

            /**
             * Destructor.
             */
            ~StatusDiagnosticRecord();

            /**
             * Get class origin.
             * 
             * @return A string that indicates the document that defines the
             *         class portion of the SQLSTATE value in this record.
             */
            const std::string& GetClassOrigin() const;

            /**
             * Get subclass origin.
             *
             * @return A string with the same format and valid values as origin,
             *         that identifies the defining portion of the subclass
             *         portion of the SQLSTATE code.
             */
            const std::string& GetSubclassOrigin() const;

            /**
             * Get record message text.
             *
             * @return An informational message on the error or warning.
             */
            const std::string& GetMessage() const;

            /**
             * Get connection name.
             *
             * @return A string that indicates the name of the connection that
             *         the diagnostic record relates to.
             */
            const std::string& GetConnectionName() const;

            /**
             * Get server name.
             *
             * @return A string that indicates the server name that the
             *         diagnostic record relates to.
             */
            const std::string& GetServerName() const;

            /**
             * Get SQL state of the record.
             *
             * @return A five-character SQLSTATE diagnostic code.
             */
            const std::string& GetSqlState() const;

            /**
             * Get row number.
             *
             * @return The row number in the rowset, or the parameter number in
             *         the set of parameters, with which the status record is 
             *         associated.
             */
            int32_t GetRowNumber() const;

            /**
             * Get column number.
             *
             * @return Contains the value that represents the column number
             *         in the result set or the parameter number in the set
             *         of parameters.
             */
            int32_t GetColumnNumber() const;

        private:
            /** SQL state diagnostic code. */
            SqlState sqlState;

            /** An informational message on the error or warning. */
            std::string message;

            /**
             * A string that indicates the name of the connection that
             * the diagnostic record relates to.
             */
            std::string connectionName;

            /**
             * A string that indicates the server name that the
             * diagnostic record relates to.
             */
            std::string serverName;

            /**
             * The row number in the rowset, or the parameter number in the
             * set of parameters, with which the status record is associated.
             */
            int32_t rowNum;

            /**
             * Contains the value that represents the column number in the
             * result set or the parameter number in the set of parameters.
             */
            int32_t columnNum;
        };

        /**
         * Diagnostic record.
         *
         * Associated with each environment, connection, statement, and
         * descriptor handle are diagnostic records. These records contain
         * diagnostic information about the last function called that used
         * a particular handle. The records are replaced only when another
         * function is called using that handle. There is no limit to the
         * number of diagnostic records that can be stored at any one time.
         *
         * This class provides interface for interaction with all handle
         * diagnostic records. That means both header and status records.
         */
        class HeaderDiagnosticRecord
        {
        public:
            /**
             * Default constructor.
             */
            HeaderDiagnosticRecord();

            /**
             * Destructor.
             */
            ~HeaderDiagnosticRecord();

            /**
             * Set header record values.
             *
             * @param retCode Operation return code.
             */
            void SetHeaderRecord(SqlResult result);

            /**
             * Add status record to diagnostic records.
             *
             * @param record Status record.
             */
            void AddStatusRecord(const StatusDiagnosticRecord& record);

            /**
             * Reset diagnostic records state.
             */
            void Reset();

            /**
             * Get result of the last operation.
             *
             * @return Result of the last operation.
             */
            SqlResult GetOperaionResult() const;

            /**
             * Get return code of the last operation.
             *
             * @return Return code of the last operation.
             */
            int GetReturnCode() const;

            /**
             * Get row count.
             *
             * @return Count of rows in cursor.
             */
            int64_t GetRowCount() const;

            /**
             * Get dynamic function.
             *
             * @return String that describes the SQL statement
             *         that the underlying function executed.
             */
            const std::string& GetDynamicFunction() const;

            /**
             * Get dynamic function code.
             *
             * @return Numeric code that describes the
             *         SQL statement that was executed.
             */
            int32_t GetDynamicFunctionCode() const;

            /**
             * Get number of rows affected.
             *
             * @return The number of rows affected by an insert,
             *         delete, or update performed by the last operation.
             */
            int32_t GetRowsAffected() const;

            /**
             * Get status records number.
             *
             * @return Number of status records.
             */
            int32_t GetStatusRecordsNumber() const;

            /**
             * Get specified status record.
             *
             * @param idx Status record index.
             * @return Status record instance reference.
             */
            const StatusDiagnosticRecord& GetStatusRecord(int32_t idx) const;

            /**
             * Check if the record is in the success state.
             *
             * @return True if the record is in the success state.
             */
            bool IsSuccessful() const;

            /**
             * Get value of the field and put it in buffer.
             *
             * @param recNum Diagnostic record number.
             * @param field Record field.
             * @param buffer Buffer to put data to.
             * @return Operation result.
             */
            SqlResult GetField(int32_t recNum, DiagnosticField field, app::ApplicationDataBuffer& buffer) const;

        private:
            IGNITE_NO_COPY_ASSIGNMENT(HeaderDiagnosticRecord);

            /**
             * Header record field. This field contains the count of rows
             * in the cursor.
             */
             int64_t rowCount;

            /**
             * Header record field. String that describes the SQL statement
             * that the underlying function executed.
             */
            std::string dynamicFunction;

            /**
             * Header record field. Numeric code that describes the
             * SQL statement that was executed.
             */
            int32_t dynamicFunctionCode;

            /**
             * Operation result. This field is mapped to "Return code" header
             * record field.
             */
            SqlResult result;

            /**
             * Header record field. The number of rows affected by an insert,
             * delete, or update performed by the last operation.
             */
            int32_t rowsAffected;

            /** Status records. */
            std::vector<StatusDiagnosticRecord> statusRecords;
        };
    }
}

#endif
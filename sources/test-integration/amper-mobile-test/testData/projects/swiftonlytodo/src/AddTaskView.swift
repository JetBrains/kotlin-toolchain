import SwiftUI
import KotlinModules


struct AddTaskView: View {
    let viewModel: ToDoViewModel
    @State private var taskText: String = ""

    var body: some View {
        VStack {
            TextField("Task Name", text: $taskText)
                .textFieldStyle(RoundedBorderTextFieldStyle())
                .padding()

            HStack {
                Button("Cancel") {
                    taskText = ""
                }
                Spacer()
                Button("Add") {
                    if !taskText.isEmpty {
                        viewModel.addItem(text: taskText)
                        taskText = ""
                    }
                }
            }
            .padding()
        }
    }
}
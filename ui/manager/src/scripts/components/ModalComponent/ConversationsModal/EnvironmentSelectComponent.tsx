import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import Select from 'react-select';
import * as _ from 'lodash';
import { CSSProperties } from 'react';

const styling: CSSProperties = {
  dropDown: {
    maxWidth: '40%',
  },
};

interface IOption {
  value: string;
  label: string;
}

interface IState {
  options: IOption[];
  option: IOption;
}
interface IProps {
  select: (string) => void;
}

class EnvironmentSelectComponent extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      options: [],
      option: null,
    };
  }

  componentDidMount() {
    const options = [
      { value: 'restricted', label: 'restricted' },
      { value: 'unrestricted', label: 'unrestricted' },
    ];
    this.setState({
      options,
    });
  }

  handleSelect = option => {
    if (!_.isEmpty(option)) {
      this.setState({ option: this.state.options[option] });
      this.props.select(option.value);
    }
  };

  render() {
    return (
      <div style={styling.dropDown}>
        <Select
          placeholder={'Search and add plugins'}
          value={this.state.option}
          options={this.state.options}
          onChange={this.handleSelect}
        />
      </div>
    );
  }
}

const ComposedEnvironmentSelectComponent: Component<IProps> = compose<IProps>(
  pure,
  setDisplayName('EnvironmentSelectComponent'),
)(EnvironmentSelectComponent);

export default ComposedEnvironmentSelectComponent;

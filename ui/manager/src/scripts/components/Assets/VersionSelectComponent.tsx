import * as React from 'react';
import * as Radium from 'radium';
import * as renderIf from 'render-if';
import { Component, compose, pure, setDisplayName } from 'recompose';
import Select from 'react-select';
import * as _ from 'lodash';
import { CSSProperties } from 'react';
import Parser from '../utils/Parser';

const styles: CSSProperties = {
  selectContainer: {
    width: '80px',
  },
};

const customStyles = {
  indicatorsContainer: (base, state) => ({
    position: 'relative',
    borderLeft: '6px solid transparent',
    borderRight: '6px solid transparent',
    borderTop: '6px solid #16325C',
    height: '0',
    marginRight: '10px',
    width: '0',
  }),
  input: (base, state) => ({
    ...base,
    maxWidth: '60px',
    overflow: 'hidden',
  }),
  valueContainer: (base, state) => ({
    ...base,
    backgroundColor: '#FFF',
    color: '#16325C',
    fontSize: '12px',
    overflow: 'hidden',
    marginLeft: '1px',
  }),
  option: (base, state) => ({
    ...base,
    color: '#16325C',
    fontSize: '12px',
    overflow: 'hidden',
  }),
};

interface IOption {
  value: number;
  label: string;
}
interface IState {
  options: IOption[];
  selectedOption: IOption;
}
interface IProps {
  currentVersion: number;
  selectedVersion: number;
  selectVersion(version: number): void;
}

class VersionSelectComponent extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      selectedOption: null,
      options: [],
    };
  }

  componentDidMount() {
    this.setOptions();
  }

  componentWillReceiveProps(nextProps) {
    this.setOptions(nextProps);
  }
  setOptions(props = this.props): void {
    const options = _.times(props.currentVersion, i => ({
      value: ++i,
      label: Parser.getVersionString(i),
    })).reverse();
    this.setState({
      options,
      selectedOption: options[options.length - props.selectedVersion],
    });
  }

  handleSelect = (option: IOption) => {
    if (!_.isEmpty(option)) {
      this.setState({ selectedOption: option });
      this.props.selectVersion(option.value);
    }
  };

  getStyles() {
    if (this.props.selectedVersion === this.props.currentVersion) {
      return {
        ...customStyles,
        control: styles => ({
          ...styles,
          backgroundColor: 'white',
          border: '1px solid green',
        }),
      };
    } else {
      return {
        ...customStyles,
        control: styles => ({
          ...styles,
          backgroundColor: 'white',
          border: '1px solid red',
        }),
      };
    }
  }

  render() {
    return (
      <div style={styles.selectContainer}>
        <Select
          styles={this.getStyles()}
          value={this.state.selectedOption}
          options={this.state.options}
          onChange={this.handleSelect}
        />
      </div>
    );
  }
}

const ComposedVersionSelectComponent: Component<IProps> = compose<IProps>(
  pure,
  setDisplayName('VersionSelectComponent'),
)(VersionSelectComponent);

export default ComposedVersionSelectComponent;
